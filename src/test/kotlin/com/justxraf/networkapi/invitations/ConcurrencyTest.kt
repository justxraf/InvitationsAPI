package com.justxraf.networkapi.invitations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Concurrency and correctness coverage for Â§4 of the roadmap. These tests hammer the manager from
 * many threads and then assert the three indexes ([byId], [byInvited], [byInviter]), the live timer
 * set, and the store never drift out of agreement â€” the invariant the [lock] exists to protect.
 *
 * The scheduler here is deliberately *real* (a thread pool), unlike the inline `FakeScheduler` of the
 * single-threaded tests: callbacks fire on other threads, so a test that passes here proves the
 * locking holds under genuine contention rather than under a serialised fake.
 */
private data class ConcInvite(
    override val id: UUID = UUID.randomUUID(),
    override val inviterId: UUID,
    override val invitedId: UUID,
    override val createdAt: Long = 0,
    override val expiresAt: Long? = null,
) : Invitation

class ConcurrencyTest {

    /** A real multi-threaded scheduler. `runLater` arms a timer on a shared scheduled executor. */
    private class ThreadedScheduler : Scheduler, AutoCloseable {
        private val main = Executors.newSingleThreadExecutor()
        private val timer = Executors.newScheduledThreadPool(4)
        private val started = System.nanoTime()

        override fun now(): Long = (System.nanoTime() - started) / 1_000_000
        override fun runOnMainThread(block: () -> Unit) { main.execute(block) }
        override fun runLater(delayMillis: Long, block: () -> Unit): Scheduler.Cancellable {
            val future = timer.schedule(block, delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            return object : Scheduler.Cancellable {
                override fun cancel() { future.cancel(false) }
            }
        }

        /** Block until every queued main-thread callback has run, so observers/hooks have settled. */
        fun drainMainThread() {
            val done = CountDownLatch(1)
            main.execute { done.countDown() }
            check(done.await(10, TimeUnit.SECONDS)) { "main-thread queue did not drain" }
        }

        override fun close() {
            main.shutdownNow()
            timer.shutdownNow()
        }
    }

    private class CountingHandler : InvitationHandler<ConcInvite> {
        val sends = AtomicInteger()
        val accepts = AtomicInteger()
        val denies = AtomicInteger()
        val cancels = AtomicInteger()
        val expires = AtomicInteger()
        override fun onSend(i: ConcInvite) { sends.incrementAndGet() }
        override fun onAccept(i: ConcInvite) { accepts.incrementAndGet() }
        override fun onDeny(i: ConcInvite) { denies.incrementAndGet() }
        override fun onCancel(i: ConcInvite, reason: CancelReason) { cancels.incrementAndGet() }
        override fun onExpire(i: ConcInvite) { expires.incrementAndGet() }
    }

    /**
     * A store that mirrors the manager's expected persisted state so a test can compare the two after
     * the dust settles. Every mutator is synchronized so the store is itself linearizable.
     */
    private class TrackingStore : InvitationStore<ConcInvite> {
        private val rows = LinkedHashMap<UUID, ConcInvite>()
        @Synchronized override fun save(invitation: ConcInvite) { rows[invitation.id] = invitation }
        @Synchronized override fun remove(id: UUID) { rows.remove(id) }
        @Synchronized override fun removeAll(ids: Collection<UUID>) { ids.forEach { rows.remove(it) } }
        @Synchronized override fun replace(old: UUID, new: ConcInvite) { rows.remove(old); rows[new.id] = new }
        @Synchronized override fun load(): List<ConcInvite> = rows.values.toList()
        @Synchronized fun ids(): Set<UUID> = rows.keys.toSet()
    }

    private fun manager(
        handler: InvitationHandler<ConcInvite>,
        scheduler: Scheduler,
        store: InvitationStore<ConcInvite> = TrackingStore(),
        maxPerInviter: Int? = null,
        maxPerInvited: Int? = null,
    ) = InvitationManager.builder(handler, scheduler)
        .store(store)
        .maxPerInviter(maxPerInviter)
        .maxPerInvited(maxPerInvited)
        .build()

    /** Run [block] on [threads] threads, releasing them simultaneously, and wait for all to finish. */
    private fun race(threads: Int, block: (Int) -> Unit) {
        val pool = Executors.newFixedThreadPool(threads)
        val barrier = CyclicBarrier(threads)
        val done = CountDownLatch(threads)
        repeat(threads) { idx ->
            pool.execute {
                try {
                    barrier.await()
                    block(idx)
                } finally {
                    done.countDown()
                }
            }
        }
        check(done.await(30, TimeUnit.SECONDS)) { "threads did not finish in time" }
        pool.shutdownNow()
    }

    /**
     * The central invariant check: every id in [InvitationManager.all] is reachable from both reverse
     * indexes and nothing extra lingers, and the store's persisted id set matches the in-memory set.
     */
    private fun assertConsistent(m: InvitationManager<ConcInvite>, store: TrackingStore) {
        val all = m.all()
        val ids = all.map { it.id }.toSet()
        assertEquals(ids.size, all.size, "duplicate ids leaked into byId")
        for (inv in all) {
            assertTrue(m.getInvitesFor(inv.invitedId).any { it.id == inv.id }, "byInvited missing ${inv.id}")
            assertTrue(m.getInvitesFrom(inv.inviterId).any { it.id == inv.id }, "byInviter missing ${inv.id}")
        }
        // No reverse-index entry should point at an id that byId no longer knows.
        for (inv in all) {
            assertEquals(inv, m[inv.id], "byId disagrees for ${inv.id}")
        }
        assertEquals(ids, store.ids(), "store and memory drifted apart")
        assertEquals(ids.size, m.pendingCount(), "pendingCount disagrees with all()")
    }

    @Test @Timeout(60)
    fun `concurrent distinct sends all register without drift`() {
        val store = TrackingStore()
        val h = CountingHandler()
        ThreadedScheduler().use { sched ->
            val m = manager(h, sched, store)
            val n = 200
            // Distinct pairs: every send must succeed.
            val invites = (0 until n).map { ConcInvite(inviterId = UUID.randomUUID(), invitedId = UUID.randomUUID()) }
            val accepted = AtomicInteger()
            race(threads = 16) { t ->
                var i = t
                while (i < n) {
                    if (m.send(invites[i]) is InvitationManager.SendResult.Accepted) accepted.incrementAndGet()
                    i += 16
                }
            }
            sched.drainMainThread()
            assertEquals(n, accepted.get())
            assertEquals(n, m.pendingCount())
            assertConsistent(m, store)
        }
    }

    @Test @Timeout(60)
    fun `duplicate sends of the same pair register exactly one`() {
        val store = TrackingStore()
        val h = CountingHandler()
        ThreadedScheduler().use { sched ->
            val m = manager(h, sched, store)
            val inviter = UUID.randomUUID()
            val invited = UUID.randomUUID()
            val accepted = AtomicInteger()
            val duplicates = AtomicInteger()
            race(threads = 16) {
                when (m.send(ConcInvite(inviterId = inviter, invitedId = invited))) {
                    is InvitationManager.SendResult.Accepted -> accepted.incrementAndGet()
                    is InvitationManager.SendResult.Duplicate -> duplicates.incrementAndGet()
                    else -> {}
                }
            }
            sched.drainMainThread()
            assertEquals(1, accepted.get(), "exactly one of the racing sends should win")
            assertEquals(15, duplicates.get())
            assertEquals(1, m.pendingCount())
            assertConsistent(m, store)
        }
    }

    @Test @Timeout(60)
    fun `maxPerInviter is never exceeded under contention`() {
        val store = TrackingStore()
        val h = CountingHandler()
        ThreadedScheduler().use { sched ->
            val cap = 5
            val m = manager(h, sched, store, maxPerInviter = cap)
            val inviter = UUID.randomUUID()
            val accepted = AtomicInteger()
            // 100 distinct targets racing to send from one inviter; only `cap` may stick.
            val invites = (0 until 100).map { ConcInvite(inviterId = inviter, invitedId = UUID.randomUUID()) }
            race(threads = 16) { t ->
                var i = t
                while (i < invites.size) {
                    if (m.send(invites[i]) is InvitationManager.SendResult.Accepted) accepted.incrementAndGet()
                    i += 16
                }
            }
            sched.drainMainThread()
            assertEquals(cap, accepted.get())
            assertEquals(cap, m.countFrom(inviter))
            assertConsistent(m, store)
        }
    }

    @Test @Timeout(60)
    fun `accept deny and cancel races consume each invite at most once`() {
        val store = TrackingStore()
        val h = CountingHandler()
        ThreadedScheduler().use { sched ->
            val m = manager(h, sched, store)
            val n = 300
            val invites = (0 until n).map { ConcInvite(inviterId = UUID.randomUUID(), invitedId = UUID.randomUUID()) }
            invites.forEach { m.send(it) }
            sched.drainMainThread()
            assertEquals(n, m.pendingCount())

            // For each invite, three threads race to accept/deny/cancel it; only one may win.
            val winners = ConcurrentLinkedQueue<UUID>()
            val pool = Executors.newFixedThreadPool(24)
            val done = CountDownLatch(n * 3)
            for (inv in invites) {
                pool.execute { if (m.accept(inv.id)) winners.add(inv.id); done.countDown() }
                pool.execute { if (m.deny(inv.id)) winners.add(inv.id); done.countDown() }
                pool.execute { if (m.cancel(inv.id)) winners.add(inv.id); done.countDown() }
            }
            check(done.await(30, TimeUnit.SECONDS))
            pool.shutdownNow()
            sched.drainMainThread()

            // Exactly one terminal op won per invite â€” no id consumed twice.
            assertEquals(n, winners.size)
            assertEquals(n, winners.toSet().size, "an invite was consumed more than once")
            assertEquals(0, m.pendingCount())
            assertEquals(n, h.accepts.get() + h.denies.get() + h.cancels.get())
            assertConsistent(m, store)
        }
    }

    @Test @Timeout(60)
    fun `bulk clears race with terminal ops without drift`() {
        val store = TrackingStore()
        val h = CountingHandler()
        ThreadedScheduler().use { sched ->
            val m = manager(h, sched, store)
            val inviter = UUID.randomUUID()
            val n = 200
            val invites = (0 until n).map { ConcInvite(inviterId = inviter, invitedId = UUID.randomUUID()) }
            invites.forEach { m.send(it) }
            sched.drainMainThread()

            // One thread clears everything from the inviter while others deny individual invites.
            race(threads = 8) { t ->
                if (t == 0) {
                    m.cancelAllFrom(inviter)
                } else {
                    var i = t
                    while (i < n) { m.deny(invites[i].id); i += 8 }
                }
            }
            sched.drainMainThread()

            assertEquals(0, m.pendingCount(), "everything should have been consumed exactly once")
            assertConsistent(m, store)
        }
    }

    @Test @Timeout(60)
    fun `expiry callbacks race with accepts without double consumption`() {
        val store = TrackingStore()
        val h = CountingHandler()
        ThreadedScheduler().use { sched ->
            val m = manager(h, sched, store)
            val n = 200
            // Very short expiry so timers fire right as the accept storm hits.
            val invites = (0 until n).map {
                ConcInvite(inviterId = UUID.randomUUID(), invitedId = UUID.randomUUID(), expiresAt = sched.now() + 30)
            }
            invites.forEach { m.send(it) }

            val acceptedById = ConcurrentLinkedQueue<UUID>()
            race(threads = 16) { t ->
                var i = t
                while (i < n) {
                    if (m.accept(invites[i].id)) acceptedById.add(invites[i].id)
                    i += 16
                }
            }
            // Let any not-yet-accepted invites expire.
            Thread.sleep(100)
            sched.drainMainThread()

            // Every invite ended exactly once: as an accept or an expiry, never both.
            assertEquals(n, h.accepts.get() + h.expires.get(),
                "an invite was both accepted and expired")
            assertEquals(0, m.pendingCount())
            assertConsistent(m, store)
        }
    }

    @Test @Timeout(60)
    fun `rehydrate after a live send does not double-index`() {
        val store = TrackingStore()
        val h = CountingHandler()
        ThreadedScheduler().use { sched ->
            val m = manager(h, sched, store)
            // Pre-seed the store directly so rehydrate has rows to load.
            val seeded = (0 until 50).map { ConcInvite(inviterId = UUID.randomUUID(), invitedId = UUID.randomUUID()) }
            seeded.forEach { store.save(it) }

            // Race a rehydrate against live sends. Even an id already loaded must not be indexed twice.
            val live = (0 until 50).map { ConcInvite(inviterId = UUID.randomUUID(), invitedId = UUID.randomUUID()) }
            race(threads = 4) { t ->
                if (t == 0) m.rehydrate() else { var i = t; while (i < live.size) { m.send(live[i]); i += 4 } }
            }
            sched.drainMainThread()
            assertConsistent(m, store)
        }
    }

    @Test @Timeout(60)
    fun `shutdown then terminal ops and a second shutdown are safe`() {
        val store = TrackingStore()
        val h = CountingHandler()
        ThreadedScheduler().use { sched ->
            val m = manager(h, sched, store)
            val inv = ConcInvite(inviterId = UUID.randomUUID(), invitedId = UUID.randomUUID())
            m.send(inv)
            sched.drainMainThread()

            m.shutdown()
            // Terminal ops after shutdown must not throw; the invite is still indexed (shutdown only
            // cancels timers), so a post-shutdown accept still succeeds exactly once.
            assertTrue(m.accept(inv.id))
            assertFalse(m.accept(inv.id))
            m.shutdown() // idempotent
            sched.drainMainThread()
            assertConsistent(m, store)
        }
    }

    @Test @Timeout(60)
    fun `double rehydrate keeps each invite once`() {
        val store = TrackingStore()
        val h = CountingHandler()
        ThreadedScheduler().use { sched ->
            val m = manager(h, sched, store)
            val seeded = (0 until 30).map { ConcInvite(inviterId = UUID.randomUUID(), invitedId = UUID.randomUUID()) }
            seeded.forEach { store.save(it) }

            val first = m.rehydrate()
            val second = m.rehydrate()
            sched.drainMainThread()

            assertEquals(30, first)
            assertEquals(0, second, "a second rehydrate must not re-index already-live invites")
            assertEquals(30, m.pendingCount())
            assertConsistent(m, store)
        }
    }

    @Test @Timeout(60)
    fun `already-expired invite never fires onSend after onExpire`() {
        // Single-threaded but deterministic: an invite whose expiry is already in the past must be
        // expired inline and never announced as a send. (Â§4: onSend not fired after onExpire.)
        val ordered = ConcurrentLinkedQueue<String>()
        val firstExpireAt = AtomicLong(-1)
        val h = object : InvitationHandler<ConcInvite> {
            override fun onSend(i: ConcInvite) { ordered += "send" }
            override fun onExpire(i: ConcInvite) { ordered += "expire" }
        }
        ThreadedScheduler().use { sched ->
            val m = manager(h, sched, store = TrackingStore())
            val inv = ConcInvite(inviterId = UUID.randomUUID(), invitedId = UUID.randomUUID(),
                expiresAt = sched.now() - 1)
            val result = m.send(inv)
            sched.drainMainThread()

            // Accepted id is still returned, but the invite is gone and only an expire fired.
            assertTrue(result is InvitationManager.SendResult.Accepted)
            assertEquals(0, m.pendingCount())
            assertEquals(listOf("expire"), ordered.toList())
            firstExpireAt.get() // touch to keep the field meaningful
        }
    }

    @Test @Timeout(60)
    fun `store and hook exceptions under ISOLATE do not corrupt indexes`() {
        // A store that throws on some removes and a handler that throws on some hooks: under the
        // default ISOLATE + MUTATE_THEN_RETRY-style resilience, memory stays consistent.
        val throwingStore = object : InvitationStore<ConcInvite> {
            private val rows = LinkedHashMap<UUID, ConcInvite>()
            private val saves = AtomicInteger()
            @Synchronized override fun save(invitation: ConcInvite) {
                if (saves.incrementAndGet() % 7 == 0) throw RuntimeException("save boom")
                rows[invitation.id] = invitation
            }
            @Synchronized override fun remove(id: UUID) { rows.remove(id) }
            @Synchronized override fun removeAll(ids: Collection<UUID>) { ids.forEach { rows.remove(it) } }
            @Synchronized override fun replace(old: UUID, new: ConcInvite) { rows.remove(old); rows[new.id] = new }
            @Synchronized override fun load(): List<ConcInvite> = rows.values.toList()
        }
        val throwingHandler = object : InvitationHandler<ConcInvite> {
            private val n = AtomicInteger()
            override fun onSend(i: ConcInvite) { if (n.incrementAndGet() % 5 == 0) throw RuntimeException("hook boom") }
        }
        ThreadedScheduler().use { sched ->
            val m = InvitationManager.builder(throwingHandler, sched)
                .store(throwingStore)
                .storeFailurePolicy(StoreFailurePolicy.MUTATE_THEN_RETRY)
                .build()
            val n = 150
            val invites = (0 until n).map { ConcInvite(inviterId = UUID.randomUUID(), invitedId = UUID.randomUUID()) }
            race(threads = 12) { t -> var i = t; while (i < n) { m.send(invites[i]); i += 12 } }
            sched.drainMainThread()

            // Indexes must still be internally consistent even though some hooks/stores threw.
            val all = m.all()
            assertEquals(all.map { it.id }.toSet().size, all.size)
            for (inv in all) {
                assertTrue(m.getInvitesFor(inv.invitedId).any { it.id == inv.id })
                assertTrue(m.getInvitesFrom(inv.inviterId).any { it.id == inv.id })
            }
        }
    }
}
