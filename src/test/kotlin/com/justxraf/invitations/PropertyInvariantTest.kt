package com.justxraf.invitations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random

/**
 * Property-style tests: drive the manager with long, randomly generated operation sequences and assert
 * the structural invariants that must hold no matter what order operations arrive in. There is no
 * external property library — the core is dependency-free — so this is a hand-rolled random sequence
 * runner with a fixed set of seeds for reproducibility.
 *
 * The invariants checked after every operation:
 *  - the two reverse indexes (`byInvited`, `byInviter`) agree exactly with `all()`,
 *  - `byId` lookups round-trip,
 *  - the store's row set matches the in-memory id set,
 *  - the count helpers (`countFor`, `countFrom`, `pendingCount`) agree with the materialized lists,
 *  - no pair is ever indexed twice.
 */
class PropertyInvariantTest {

    private data class PropInvite(
        override val id: UUID = UUID.randomUUID(),
        override val inviterId: UUID,
        override val invitedId: UUID,
        override val createdAt: Long,
        override val expiresAt: Long? = null,
    ) : Invitation

    /** Inline, single-threaded scheduler with a manually advanced clock for deterministic expiry. */
    private class FakeScheduler : Scheduler {
        var now = 0L
        private data class Task(val fireAt: Long, val block: () -> Unit, var cancelled: Boolean = false)
        private val tasks = mutableListOf<Task>()

        override fun now(): Long = now
        override fun runOnMainThread(block: () -> Unit) = block()
        override fun runLater(delayMillis: Long, block: () -> Unit): Scheduler.Cancellable {
            val task = Task(now + delayMillis.coerceAtLeast(0), block)
            tasks += task
            return object : Scheduler.Cancellable { override fun cancel() { task.cancelled = true } }
        }

        /** Advance time by [millis] and fire every timer whose deadline has passed, in order. */
        fun advance(millis: Long) {
            now += millis
            while (true) {
                val due = tasks.filter { !it.cancelled && it.fireAt <= now }.minByOrNull { it.fireAt } ?: break
                tasks.remove(due)
                due.block()
            }
        }
    }

    private class TrackingStore : InvitationStore<PropInvite> {
        private val rows = LinkedHashMap<UUID, PropInvite>()
        override fun save(invitation: PropInvite) { rows[invitation.id] = invitation }
        override fun remove(id: UUID) { rows.remove(id) }
        override fun removeAll(ids: Collection<UUID>) { ids.forEach { rows.remove(it) } }
        override fun replace(old: UUID, new: PropInvite) { rows.remove(old); rows[new.id] = new }
        override fun load(): List<PropInvite> = rows.values.toList()
        fun ids(): Set<UUID> = rows.keys.toSet()
    }

    private fun assertInvariants(m: InvitationManager<PropInvite>, store: TrackingStore) {
        val all = m.all()
        val ids = all.map { it.id }.toSet()
        assertEquals(all.size, ids.size, "duplicate ids in all()")
        for (inv in all) {
            assertEquals(inv, m[inv.id], "byId disagrees for ${inv.id}")
            assertTrue(m.getInvitesFor(inv.invitedId).any { it.id == inv.id }, "byInvited missing ${inv.id}")
            assertTrue(m.getInvitesFrom(inv.inviterId).any { it.id == inv.id }, "byInviter missing ${inv.id}")
        }
        // No pair indexed twice.
        val pairs = all.map { it.inviterId to it.invitedId }
        assertEquals(pairs.size, pairs.toSet().size, "a pair is indexed more than once")
        assertEquals(ids, store.ids(), "store and memory drifted apart")
        assertEquals(ids.size, m.pendingCount(), "pendingCount disagrees with all()")
        for (inv in all) {
            assertEquals(m.getInvitesFor(inv.invitedId).size, m.countFor(inv.invitedId), "countFor mismatch")
            assertEquals(m.getInvitesFrom(inv.inviterId).size, m.countFrom(inv.inviterId), "countFrom mismatch")
        }
    }

    @Test
    fun `random operation sequences never break index or store invariants`() {
        // A small player pool maximizes collisions: duplicate pairs, caps, and cross-direction clears.
        val players = (0 until 8).map { UUID.randomUUID() }

        for (seed in 0L until 25L) {
            val rng = Random(seed)
            val sched = FakeScheduler()
            val store = TrackingStore()
            val m = InvitationManager
                .builder(object : InvitationHandler<PropInvite> {}, sched)
                .store(store)
                .maxPerInviter(4)
                .maxPerInvited(4)
                .duplicatePolicy(DuplicatePolicy.entries[seed.toInt() % DuplicatePolicy.entries.size])
                .build()

            repeat(400) {
                val inviter = players.random(rng)
                val invited = players.random(rng)
                when (rng.nextInt(9)) {
                    0, 1, 2 -> {
                        val expires = if (rng.nextBoolean()) sched.now() + rng.nextLong(1, 50) else null
                        m.send(PropInvite(inviterId = inviter, invitedId = invited, createdAt = sched.now(), expiresAt = expires))
                    }
                    3 -> m.getInvite(inviter, invited)?.let { m.accept(it.id) }
                    4 -> m.getInvite(inviter, invited)?.let { m.deny(it.id) }
                    5 -> m.getInvite(inviter, invited)?.let { m.cancel(it.id) }
                    6 -> m.denyAll(invited)
                    7 -> m.cancelAllFrom(inviter)
                    8 -> { sched.advance(rng.nextLong(1, 30)); m.sweepExpired() }
                }
                assertInvariants(m, store)
            }
            // Drain any remaining timers; invariants must still hold at the end.
            sched.advance(1000)
            m.sweepExpired()
            assertInvariants(m, store)
        }
    }
}
