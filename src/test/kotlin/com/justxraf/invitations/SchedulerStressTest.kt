package com.justxraf.invitations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stress tests targeting the scheduler seam specifically: thousands of short-lived expiry timers firing
 * on a real thread pool while terminal actions race to cancel them. The contract under test is that
 * every invitation is consumed exactly once — either expired by its timer or terminated by a caller,
 * never both, never neither — and that cancelled timers actually stop firing (no leaked tasks, no
 * onExpire after a terminal action).
 */
@Tag("stress")
class SchedulerStressTest {

    private data class StressInvite(
        override val id: UUID = UUID.randomUUID(),
        override val inviterId: UUID,
        override val invitedId: UUID,
        override val createdAt: Long = 0,
        override val expiresAt: Long? = null,
    ) : Invitation

    private class RealScheduler : Scheduler, AutoCloseable {
        private val main = Executors.newSingleThreadExecutor()
        private val timer = java.util.concurrent.ScheduledThreadPoolExecutor(8).apply {
            // So a cancelled task is purged from the work queue promptly, making queue size a faithful
            // signal of "timers still outstanding" rather than counting tombstoned cancellations.
            removeOnCancelPolicy = true
        }
        private val started = System.nanoTime()

        /** Tasks still queued or running — the real "are any timers outstanding" signal. */
        fun outstandingTimers(): Int = timer.queue.size + timer.activeCount

        override fun now(): Long = (System.nanoTime() - started) / 1_000_000
        override fun runOnMainThread(block: () -> Unit) { main.execute(block) }
        override fun runLater(delayMillis: Long, block: () -> Unit): Scheduler.Cancellable {
            val future = timer.schedule(block, delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            return object : Scheduler.Cancellable {
                override fun cancel() { future.cancel(false) }
            }
        }

        fun drainMainThread() {
            val done = CountDownLatch(1)
            main.execute { done.countDown() }
            check(done.await(20, TimeUnit.SECONDS)) { "main-thread queue did not drain" }
        }

        override fun close() { main.shutdownNow(); timer.shutdownNow() }
    }

    @Test @Timeout(120)
    fun `thousands of expiry timers racing with terminal actions consume each invite exactly once`() {
        RealScheduler().use { sched ->
            val expired = AtomicInteger()
            val handler = object : InvitationHandler<StressInvite> {
                override fun onExpire(i: StressInvite) { expired.incrementAndGet() }
            }
            val m = InvitationManager.builder(handler, sched).store(InvitationStore.InMemory()).build()

            val n = 4_000
            // Stagger expiries across a narrow window so timers and terminal actions genuinely overlap.
            val invites = (0 until n).map {
                StressInvite(
                    inviterId = UUID.randomUUID(),
                    invitedId = UUID.randomUUID(),
                    expiresAt = sched.now() + 20 + (it % 40),
                )
            }
            invites.forEach { m.send(it) }

            val terminated = ConcurrentHashMap.newKeySet<UUID>()
            val pool = Executors.newFixedThreadPool(16)
            val done = CountDownLatch(n)
            // Half the invites get a racing terminal action; the rest are left to expire on their own.
            invites.forEachIndexed { idx, inv ->
                pool.execute {
                    try {
                        if (idx % 2 == 0 && m.accept(inv.id)) terminated.add(inv.id)
                    } finally {
                        done.countDown()
                    }
                }
            }
            check(done.await(60, TimeUnit.SECONDS))
            pool.shutdownNow()

            // Let every remaining expiry timer fire.
            Thread.sleep(300)
            sched.drainMainThread()

            assertEquals(0, m.pendingCount(), "everything must be consumed")
            assertEquals(
                n,
                terminated.size + expired.get(),
                "each invite must be either accepted or expired, never both or neither",
            )
            // Timer bookkeeping settles asynchronously (a cancelled-but-already-running body still has
            // to reach its finally); poll for it rather than asserting on a single instant.
            assertEventually("expiry timers leaked after all invites were consumed") { sched.outstandingTimers() == 0 }
        }
    }

    @Test @Timeout(60)
    fun `cancelling via terminal actions leaves no live expiry timers`() {
        RealScheduler().use { sched ->
            val m = InvitationManager
                .builder(object : InvitationHandler<StressInvite> {}, sched)
                .store(InvitationStore.InMemory())
                .expiryWarningOffsetsMillis(5_000, 2_000) // each invite also arms warning timers
                .build()

            val n = 2_000
            val invites = (0 until n).map {
                StressInvite(
                    inviterId = UUID.randomUUID(),
                    invitedId = UUID.randomUUID(),
                    expiresAt = sched.now() + 60_000,
                )
            }
            invites.forEach { m.send(it) }
            assertTrue(sched.outstandingTimers() > 0, "timers should be armed")

            // Consume them all before any timer could fire; all expiry + warning timers must be cancelled.
            invites.forEach { m.cancel(it.id) }
            sched.drainMainThread()

            assertEquals(0, m.pendingCount())
            assertEventually("expiry/warning timers leaked after cancellation") { sched.outstandingTimers() == 0 }
        }
    }

    private fun assertEventually(message: String, timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition(), message)
    }
}
