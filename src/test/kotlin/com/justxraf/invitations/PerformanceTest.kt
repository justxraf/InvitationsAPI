package com.justxraf.invitations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.UUID

/**
 * Throughput / scaling tests for the volumes a busy Skyblock network actually reaches: tens of
 * thousands of pending invites, large per-player fan-out, and bulk clears. These are not microbenchmarks
 * — they assert correctness at scale and that core paths stay roughly linear, with generous wall-clock
 * timeouts so they double as a regression guard against an accidental O(n^2) in a lookup or bulk path.
 *
 * Tagged `performance` so CI can run them on a dedicated lane or skip them on constrained runners via
 * `-PexcludeTags=performance`.
 */
@Tag("performance")
class PerformanceTest {

    private data class PerfInvite(
        override val id: UUID = UUID.randomUUID(),
        override val inviterId: UUID,
        override val invitedId: UUID,
        override val createdAt: Long = 0,
        override val expiresAt: Long? = null,
    ) : Invitation

    private val inlineScheduler = object : Scheduler {
        override fun now() = 0L
        override fun runOnMainThread(block: () -> Unit) = block()
        override fun runLater(delayMillis: Long, block: () -> Unit) =
            object : Scheduler.Cancellable { override fun cancel() {} }
    }

    private fun manager() =
        InvitationManager
            .builder(object : InvitationHandler<PerfInvite> {}, inlineScheduler)
            .store(InvitationStore.InMemory())
            .build()

    @Test @Timeout(30)
    fun `sending and looking up 50k distinct invites stays fast`() {
        val m = manager()
        val n = 50_000
        val invites = (0 until n).map { PerfInvite(inviterId = UUID.randomUUID(), invitedId = UUID.randomUUID()) }

        val sendNanos = timed { invites.forEach { m.send(it) } }
        assertEquals(n, m.pendingCount())

        // Random id lookups must be effectively O(1).
        val lookupNanos = timed { invites.forEach { assertTrue(m[it.id] != null) } }

        println("send 50k: ${sendNanos / 1_000_000}ms, 50k id lookups: ${lookupNanos / 1_000_000}ms")
    }

    @Test @Timeout(30)
    fun `large per-inviter fan-out lookup and clear scale linearly`() {
        val m = manager()
        val inviter = UUID.randomUUID()
        val n = 20_000
        repeat(n) { m.send(PerfInvite(inviterId = inviter, invitedId = UUID.randomUUID())) }
        assertEquals(n, m.countFrom(inviter))

        val listNanos = timed { repeat(50) { assertEquals(n, m.getInvitesFrom(inviter).size) } }
        val clearNanos = timed { assertEquals(n, m.cancelAllFrom(inviter)) }
        assertEquals(0, m.pendingCount())

        println("50x fan-out list of $n: ${listNanos / 1_000_000}ms, bulk clear $n: ${clearNanos / 1_000_000}ms")
    }

    @Test @Timeout(30)
    fun `bulk denyAll over a wide receiver is a single pass`() {
        val m = manager()
        val invited = UUID.randomUUID()
        val n = 20_000
        repeat(n) { m.send(PerfInvite(inviterId = UUID.randomUUID(), invitedId = invited)) }
        val denyNanos = timed { assertEquals(n, m.denyAll(invited)) }
        assertEquals(0, m.pendingCount())
        println("bulk denyAll $n: ${denyNanos / 1_000_000}ms")
    }

    private inline fun timed(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return System.nanoTime() - start
    }
}
