package com.justxraf.invitations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
private class FakeScheduler : Scheduler {
    private class Task(val fireAtMillis: Long, val block: () -> Unit) : Scheduler.Cancellable {
        var cancelled = false
        override fun cancel() { cancelled = true }
    }
    private val tasks = mutableListOf<Task>()
    private var clock = 0L

    override fun now(): Long = clock
    override fun runOnMainThread(block: () -> Unit) = block()
    override fun runLater(delayMillis: Long, block: () -> Unit): Scheduler.Cancellable =
        Task(clock + delayMillis, block).also { tasks += it }
fun advance(millis: Long) {
        clock += millis
        tasks.filter { !it.cancelled && it.fireAtMillis <= clock }.also { tasks.removeAll(it.toSet()) }
            .forEach { it.block() }
    }
}

private data class TestInvite(
    override val id: UUID = UUID.randomUUID(),
    override val inviterId: UUID,
    override val invitedId: UUID,
    override val createdAt: Long = 0,
    override val expiresAt: Long? = null,
) : Invitation

private class RecordingHandler(val rejectReason: String? = null) : InvitationHandler<TestInvite> {
    val events = mutableListOf<String>()
    var lastValidateExisting: List<TestInvite> = emptyList()
    override fun validate(invitation: TestInvite, existing: List<TestInvite>): String? {
        lastValidateExisting = existing
        return rejectReason
    }
    override fun onSend(i: TestInvite) { events += "send" }
    override fun onAccept(i: TestInvite) { events += "accept" }
    override fun onDeny(i: TestInvite) { events += "deny" }
    override fun onCancel(i: TestInvite, reason: CancelReason) { events += "cancel:$reason" }
    override fun onExpire(i: TestInvite) { events += "expire" }
    override fun onExpiryWarning(i: TestInvite, remainingMillis: Long) { events += "warn:$remainingMillis" }
}

class InvitationManagerTest {
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()
    private val c = UUID.randomUUID()

    @Test fun `send registers and indexes both directions`() {
        val h = RecordingHandler(); val m = InvitationManager(h, FakeScheduler())
        val inv = TestInvite(inviterId = a, invitedId = b)

        val result = m.send(inv)

        assertEquals(InvitationManager.SendResult.Accepted(inv.id), result)
        assertEquals(listOf("send"), h.events)
        assertEquals(listOf(inv), m.getInvitesFor(b))
        assertEquals(listOf(inv), m.getInvitesFrom(a))
        assertEquals(inv, m.getInvite(a, b))
    }

    @Test fun `validate can reject before registering`() {
        val h = RecordingHandler(rejectReason = "nope"); val m = InvitationManager(h, FakeScheduler())

        val result = m.send(TestInvite(inviterId = a, invitedId = b))

        assertEquals(InvitationManager.SendResult.Rejected("nope"), result)
        assertTrue(h.events.isEmpty())
        assertTrue(m.getInvitesFor(b).isEmpty())
    }

    @Test fun `validate sees the inviter's existing invites`() {
        val h = RecordingHandler(); val m = InvitationManager(h, FakeScheduler())
        val first = TestInvite(inviterId = a, invitedId = b); m.send(first)

        m.send(TestInvite(inviterId = a, invitedId = c))

        assertEquals(listOf(first), h.lastValidateExisting)
    }

    @Test fun `duplicate inviter-invited pair is rejected`() {
        val m = InvitationManager(RecordingHandler(), FakeScheduler())
        val first = TestInvite(inviterId = a, invitedId = b)
        m.send(first)

        val result = m.send(TestInvite(inviterId = a, invitedId = b))

        assertEquals(InvitationManager.SendResult.Duplicate(first.id), result)
        assertEquals(1, m.getInvitesFor(b).size)
    }

    @Test fun `duplicate attempts start the pair cooldown`() {
        val m = InvitationManager(RecordingHandler(), FakeScheduler(), pairCooldownMillis = 5000)
        val first = TestInvite(inviterId = a, invitedId = b)
        m.send(first)

        val duplicate = m.send(TestInvite(inviterId = a, invitedId = b))
        val cooledDown = m.send(TestInvite(inviterId = a, invitedId = b))

        assertEquals(InvitationManager.SendResult.Duplicate(first.id), duplicate)
        assertEquals(InvitationManager.SendResult.CooldownActive(5000), cooledDown)
        assertEquals(1, m.getInvitesFor(b).size)
    }

    @Test fun `replace duplicate policy consumes the old invite and sends the new one`() {
        val store = InvitationStore.InMemory<TestInvite>()
        val sched = FakeScheduler()
        val h = RecordingHandler()
        val m = InvitationManager(
            h,
            sched,
            store = store,
            duplicatePolicy = DuplicatePolicy.REPLACE_EXISTING,
        )
        val first = TestInvite(inviterId = a, invitedId = b, expiresAt = 1000)
        val replacement = TestInvite(inviterId = a, invitedId = b, expiresAt = 5000)
        m.send(first)

        val result = m.send(replacement)

        assertEquals(InvitationManager.SendResult.Replaced(first.id, replacement.id), result)
        assertNull(m[first.id])
        assertEquals(replacement, m[replacement.id])
        assertEquals(listOf(replacement), store.load())
        assertEquals(listOf("send", "cancel:DUPLICATE_REPLACED", "send"), h.events)

        sched.advance(1001)
        assertEquals(listOf("send", "cancel:DUPLICATE_REPLACED", "send"), h.events)
        sched.advance(4000)
        assertEquals("expire", h.events.last())
    }

    @Test fun `refresh duplicate policy updates the pending invite expiry without firing send again`() {
        val store = InvitationStore.InMemory<TestInvite>()
        val sched = FakeScheduler()
        val h = RecordingHandler()
        val m = InvitationManager(
            h,
            sched,
            store = store,
            duplicatePolicy = DuplicatePolicy.REFRESH_EXPIRY,
        )
        val first = TestInvite(inviterId = a, invitedId = b, expiresAt = 1000)
        val refreshed = first.copy(expiresAt = 5000)
        m.send(first)

        val result = m.send(refreshed)

        assertEquals(InvitationManager.SendResult.Refreshed(first.id), result)
        assertEquals(refreshed, m[first.id])
        assertEquals(listOf(refreshed), store.load())
        assertEquals(listOf("send"), h.events)

        sched.advance(1001)
        assertEquals(listOf("send"), h.events)
        sched.advance(4000)
        assertEquals(listOf("send", "expire"), h.events)
    }

    @Test fun `maxPerInviter caps simultaneous invites from one inviter`() {
        val m = InvitationManager(RecordingHandler(), FakeScheduler(), maxPerInviter = 2)
        m.send(TestInvite(inviterId = a, invitedId = b))
        m.send(TestInvite(inviterId = a, invitedId = c))

        val result = m.send(TestInvite(inviterId = a, invitedId = UUID.randomUUID()))

        assertEquals(InvitationManager.SendResult.LimitReached(2), result)
        assertEquals(2, m.getInvitesFrom(a).size)
    }

    @Test fun `maxPerInvited caps simultaneous invites addressed to one player`() {
        val m = InvitationManager(RecordingHandler(), FakeScheduler(), maxPerInvited = 2)
        m.send(TestInvite(inviterId = a, invitedId = b))
        m.send(TestInvite(inviterId = c, invitedId = b))

        val result = m.send(TestInvite(inviterId = UUID.randomUUID(), invitedId = b))

        assertEquals(InvitationManager.SendResult.LimitReached(2), result)
        assertEquals(2, m.getInvitesFor(b).size)
    }

    @Test fun `accept fires hook and removes from all indexes`() {
        val h = RecordingHandler(); val m = InvitationManager(h, FakeScheduler())
        val inv = TestInvite(inviterId = a, invitedId = b); m.send(inv)

        assertTrue(m.accept(inv.id))

        assertEquals(listOf("send", "accept"), h.events)
        assertTrue(m.getInvitesFor(b).isEmpty())
        assertTrue(m.getInvitesFrom(a).isEmpty())
        assertNull(m[inv.id])
    }

    @Test fun `deny and cancel behave like accept with their own hooks`() {
        val h = RecordingHandler(); val m = InvitationManager(h, FakeScheduler())
        val i1 = TestInvite(inviterId = a, invitedId = b)
        val i2 = TestInvite(inviterId = a, invitedId = c)
        m.send(i1); m.send(i2)

        assertTrue(m.deny(i1.id))
        assertTrue(m.cancel(i2.id))

        assertEquals(listOf("send", "send", "deny", "cancel:REVOKED"), h.events)
        assertTrue(m.all().isEmpty())
    }

    @Test fun `deny and cancel start the pair cooldown`() {
        val m = InvitationManager(RecordingHandler(), FakeScheduler(), pairCooldownMillis = 3000)
        val denied = TestInvite(inviterId = a, invitedId = b)
        val cancelled = TestInvite(inviterId = a, invitedId = c)
        m.send(denied); m.send(cancelled)

        m.deny(denied.id)
        m.cancel(cancelled.id)

        assertEquals(
            InvitationManager.SendResult.CooldownActive(3000),
            m.send(TestInvite(inviterId = a, invitedId = b)),
        )
        assertEquals(
            InvitationManager.SendResult.CooldownActive(3000),
            m.send(TestInvite(inviterId = a, invitedId = c)),
        )
    }

    @Test fun `pair cooldown expires against the scheduler clock`() {
        val sched = FakeScheduler()
        val m = InvitationManager(RecordingHandler(), sched, pairCooldownMillis = 3000)
        val inv = TestInvite(inviterId = a, invitedId = b)
        m.send(inv)
        m.deny(inv.id)

        sched.advance(2999)
        assertEquals(
            InvitationManager.SendResult.CooldownActive(1),
            m.send(TestInvite(inviterId = a, invitedId = b)),
        )
        sched.advance(1)

        val result = m.send(TestInvite(inviterId = a, invitedId = b))

        assertEquals(InvitationManager.SendResult.Accepted::class, result::class)
    }

    @Test fun `accept does not start the pair cooldown`() {
        val m = InvitationManager(RecordingHandler(), FakeScheduler(), pairCooldownMillis = 3000)
        val inv = TestInvite(inviterId = a, invitedId = b)
        m.send(inv)

        m.accept(inv.id)

        val result = m.send(TestInvite(inviterId = a, invitedId = b))
        assertEquals(InvitationManager.SendResult.Accepted::class, result::class)
    }

    @Test fun `clearFor drops every invite a player is part of, in either direction`() {
        val h = RecordingHandler(); val m = InvitationManager(h, FakeScheduler())
        m.send(TestInvite(inviterId = a, invitedId = b))
        m.send(TestInvite(inviterId = c, invitedId = a))
        m.send(TestInvite(inviterId = b, invitedId = c))

        val cleared = m.clearFor(a)

        assertEquals(2, cleared)
        assertEquals(listOf("cancel:PARTY_CLEARED", "cancel:PARTY_CLEARED"), h.events.takeLast(2))
        assertTrue(m.getInvitesFrom(a).isEmpty())
        assertTrue(m.getInvitesFor(a).isEmpty())
        assertEquals(1, m.all().size)
    }

    @Test fun `consuming an unknown id is a no-op`() {
        val h = RecordingHandler(); val m = InvitationManager(h, FakeScheduler())
        assertTrue(!m.accept(UUID.randomUUID()))
        assertTrue(h.events.isEmpty())
    }

    @Test fun `accept by pair resolves and consumes the invite from a name`() {
        val h = RecordingHandler(); val m = InvitationManager(h, FakeScheduler())
        val inv = TestInvite(inviterId = a, invitedId = b); m.send(inv)

        assertTrue(m.accept(a, b))

        assertEquals(listOf("send", "accept"), h.events)
        assertNull(m[inv.id])
    }

    @Test fun `accept by pair is a no-op when no such invite exists`() {
        val h = RecordingHandler(); val m = InvitationManager(h, FakeScheduler())
        m.send(TestInvite(inviterId = a, invitedId = b))

        assertTrue(!m.accept(a, c))
        assertEquals(listOf("send"), h.events)
    }

    @Test fun `denyAll declines every invite addressed to one player`() {
        val h = RecordingHandler(); val m = InvitationManager(h, FakeScheduler())
        m.send(TestInvite(inviterId = a, invitedId = b))
        m.send(TestInvite(inviterId = c, invitedId = b))
        m.send(TestInvite(inviterId = a, invitedId = c))

        val denied = m.denyAll(b)

        assertEquals(2, denied)
        assertEquals(listOf("deny", "deny"), h.events.takeLast(2))
        assertTrue(m.getInvitesFor(b).isEmpty())
        assertEquals(1, m.all().size)
    }

    @Test fun `cancelAllFrom revokes every invite sent by one player`() {
        val h = RecordingHandler(); val m = InvitationManager(h, FakeScheduler())
        m.send(TestInvite(inviterId = a, invitedId = b))
        m.send(TestInvite(inviterId = a, invitedId = c))
        m.send(TestInvite(inviterId = b, invitedId = c))

        val cancelled = m.cancelAllFrom(a)

        assertEquals(2, cancelled)
        assertEquals(listOf("cancel:REVOKED", "cancel:REVOKED"), h.events.takeLast(2))
        assertTrue(m.getInvitesFrom(a).isEmpty())
        assertEquals(1, m.all().size)
    }

    @Test fun `getMostRecentFor returns the newest invite or null`() {
        val m = InvitationManager(RecordingHandler(), FakeScheduler())
        assertNull(m.getMostRecentFor(b))
        val older = TestInvite(inviterId = a, invitedId = b, createdAt = 100)
        val newer = TestInvite(inviterId = c, invitedId = b, createdAt = 200)
        m.send(older); m.send(newer)

        assertEquals(newer, m.getMostRecentFor(b))
    }

    @Test fun `bulk operations clear timers and the store`() {
        val store = InvitationStore.InMemory<TestInvite>(); val sched = FakeScheduler()
        val h = RecordingHandler(); val m = InvitationManager(h, sched, store = store)
        m.send(TestInvite(inviterId = a, invitedId = b, expiresAt = 1000))
        m.send(TestInvite(inviterId = c, invitedId = b, expiresAt = 1000))

        assertEquals(2, m.denyAll(b))
        assertTrue(store.load().isEmpty())
        sched.advance(5000)

        assertEquals(listOf("send", "send", "deny", "deny"), h.events)
    }

    @Test fun `builder configures store and invite caps`() {
        val store = InvitationStore.InMemory<TestInvite>()
        val m = InvitationManager.builder(RecordingHandler(), FakeScheduler())
            .maxPerInviter(1)
            .maxPerInvited(1)
            .pairCooldownMillis(2500)
            .store(store)
            .build()

        m.send(TestInvite(inviterId = a, invitedId = b))
        val inviterCapped = m.send(TestInvite(inviterId = a, invitedId = c))
        val invitedCapped = m.send(TestInvite(inviterId = c, invitedId = b))

        assertEquals(InvitationManager.SendResult.LimitReached(1), inviterCapped)
        assertEquals(InvitationManager.SendResult.LimitReached(1), invitedCapped)
        assertEquals(1, store.load().size)

        m.cancel(store.load().single().id)
        assertEquals(
            InvitationManager.SendResult.CooldownActive(2500),
            m.send(TestInvite(inviterId = a, invitedId = b)),
        )
    }

    @Test fun `builder defaults match the constructor defaults`() {
        val m = InvitationManager.builder(RecordingHandler(), FakeScheduler()).build()
        repeat(3) { m.send(TestInvite(inviterId = a, invitedId = UUID.randomUUID())) }

        assertEquals(3, m.getInvitesFrom(a).size)
    }

    @Test fun `expiry fires onExpire when the clock passes expiresAt`() {
        val h = RecordingHandler(); val sched = FakeScheduler()
        val m = InvitationManager(h, sched)
        val inv = TestInvite(inviterId = a, invitedId = b, createdAt = 0, expiresAt = 1000)
        m.send(inv)

        sched.advance(999)
        assertEquals(listOf("send"), h.events)
        sched.advance(2)
        assertEquals(listOf("send", "expire"), h.events)
        assertTrue(m.getInvitesFor(b).isEmpty())
    }

    @Test fun `expiry starts the pair cooldown`() {
        val sched = FakeScheduler()
        val m = InvitationManager(RecordingHandler(), sched, pairCooldownMillis = 3000)
        m.send(TestInvite(inviterId = a, invitedId = b, createdAt = 0, expiresAt = 1000))

        sched.advance(1000)

        assertEquals(
            InvitationManager.SendResult.CooldownActive(3000),
            m.send(TestInvite(inviterId = a, invitedId = b)),
        )
    }

    @Test fun `an already-past expiry is expired inline without onSend`() {
        val h = RecordingHandler(); val sched = FakeScheduler()
        val m = InvitationManager(h, sched)

        val result = m.send(TestInvite(inviterId = a, invitedId = b, createdAt = 0, expiresAt = -1))

        assertEquals(InvitationManager.SendResult.Accepted::class, result::class)
        assertEquals(listOf("expire"), h.events)
        assertTrue(m.getInvitesFor(b).isEmpty())
    }

    @Test fun `accepting before expiry cancels the timer so it never expires`() {
        val h = RecordingHandler(); val sched = FakeScheduler()
        val m = InvitationManager(h, sched)
        val inv = TestInvite(inviterId = a, invitedId = b, expiresAt = 1000)
        m.send(inv)

        assertTrue(m.accept(inv.id))
        sched.advance(5000)

        assertEquals(listOf("send", "accept"), h.events)
    }

    @Test fun `send writes to the store and terminal events remove from it`() {
        val store = InvitationStore.InMemory<TestInvite>()
        val m = InvitationManager(RecordingHandler(), FakeScheduler(), store = store)
        val inv = TestInvite(inviterId = a, invitedId = b)

        m.send(inv)
        assertEquals(listOf(inv), store.load())

        m.accept(inv.id)
        assertTrue(store.load().isEmpty())
    }

    @Test fun `every terminal path removes from the store`() {
        val store = InvitationStore.InMemory<TestInvite>()
        val m = InvitationManager(RecordingHandler(), FakeScheduler(), store = store)
        val denied = TestInvite(inviterId = a, invitedId = b)
        val cancelled = TestInvite(inviterId = a, invitedId = c)
        val cleared = TestInvite(inviterId = c, invitedId = a)
        m.send(denied); m.send(cancelled); m.send(cleared)
        assertEquals(3, store.load().size)

        m.deny(denied.id); m.cancel(cancelled.id); m.clearFor(a)
        assertTrue(store.load().isEmpty())
    }

    @Test fun `expiry removes from the store too`() {
        val store = InvitationStore.InMemory<TestInvite>(); val sched = FakeScheduler()
        val m = InvitationManager(RecordingHandler(), sched, store = store)
        m.send(TestInvite(inviterId = a, invitedId = b, expiresAt = 1000))

        sched.advance(1001)
        assertTrue(store.load().isEmpty())
    }

    @Test fun `rehydrate reloads pending invites and rebuilds both indexes`() {
        val store = InvitationStore.InMemory<TestInvite>()
        val i1 = TestInvite(inviterId = a, invitedId = b)
        val i2 = TestInvite(inviterId = a, invitedId = c)
        store.save(i1); store.save(i2)

        val h = RecordingHandler()
        val m = InvitationManager(h, FakeScheduler(), store = store)
        val pending = m.rehydrate()

        assertEquals(2, pending)
        assertTrue(h.events.isEmpty())
        assertEquals(setOf(i1, i2), m.getInvitesFrom(a).toSet())
        assertEquals(listOf(i1), m.getInvitesFor(b))
        assertEquals(i2, m.getInvite(a, c))
    }

    @Test fun `rehydrate re-arms expiry for invites still in their window`() {
        val store = InvitationStore.InMemory<TestInvite>()
        store.save(TestInvite(inviterId = a, invitedId = b, expiresAt = 1000))
        val h = RecordingHandler(); val sched = FakeScheduler()
        val m = InvitationManager(h, sched, store = store)

        assertEquals(1, m.rehydrate())
        sched.advance(1001)

        assertEquals(listOf("expire"), h.events)
        assertTrue(store.load().isEmpty())
    }

    @Test fun `rehydrate immediately expires invites whose expiry passed while offline`() {
        val store = InvitationStore.InMemory<TestInvite>()
        store.save(TestInvite(inviterId = a, invitedId = b, expiresAt = -1))
        val h = RecordingHandler()
        val m = InvitationManager(h, FakeScheduler(), store = store)

        val pending = m.rehydrate()

        assertEquals(0, pending)
        assertEquals(listOf("expire"), h.events)
        assertTrue(m.getInvitesFor(b).isEmpty())
        assertTrue(store.load().isEmpty())
    }

    @Test fun `shutdown cancels timers without firing hooks or touching the store`() {
        val store = InvitationStore.InMemory<TestInvite>(); val sched = FakeScheduler()
        val h = RecordingHandler()
        val m = InvitationManager(h, sched, store = store)
        val inv = TestInvite(inviterId = a, invitedId = b, expiresAt = 1000)
        m.send(inv)

        m.shutdown()
        sched.advance(5000)

        assertEquals(listOf("send"), h.events)
        assertEquals(listOf(inv), store.load())
    }

    @Test fun `self-invite is rejected by default before validate or indexing`() {
        val h = RecordingHandler(); val m = InvitationManager(h, FakeScheduler())

        val result = m.send(TestInvite(inviterId = a, invitedId = a))

        assertEquals(InvitationManager.SendResult.SelfInvite, result)
        assertTrue(h.events.isEmpty())
        assertTrue(m.getInvitesFor(a).isEmpty())
    }

    @Test fun `self-invite is allowed under ALLOW policy`() {
        val m = InvitationManager(RecordingHandler(), FakeScheduler(), selfInvitePolicy = SelfInvitePolicy.ALLOW)

        val result = m.send(TestInvite(inviterId = a, invitedId = a))

        assertEquals(InvitationManager.SendResult.Accepted::class, result::class)
        assertEquals(1, m.getInvitesFor(a).size)
    }

    @Test fun `detailed terminal results distinguish success from a no-op`() {
        val m = InvitationManager(RecordingHandler(), FakeScheduler())
        val inv = TestInvite(inviterId = a, invitedId = b); m.send(inv)

        assertEquals(AcceptResult.Accepted(inv.id), m.acceptDetailed(inv.id))
        assertEquals(AcceptResult.NotFound, m.acceptDetailed(inv.id))
        assertEquals(DenyResult.NotFound, m.denyDetailed(inv.id))
        assertEquals(CancelResult.NotFound, m.cancelDetailed(UUID.randomUUID()))
    }

    @Test fun `detailed cancel and deny report the consumed id`() {
        val m = InvitationManager(RecordingHandler(), FakeScheduler())
        val denied = TestInvite(inviterId = a, invitedId = b)
        val cancelled = TestInvite(inviterId = a, invitedId = c)
        m.send(denied); m.send(cancelled)

        assertEquals(DenyResult.Denied(denied.id), m.denyDetailed(denied.id))
        assertEquals(CancelResult.Cancelled(cancelled.id), m.cancelDetailed(cancelled.id))
    }

    @Test fun `expiry warning fires at each configured offset before expiry`() {
        val h = RecordingHandler(); val sched = FakeScheduler()
        val m = InvitationManager(h, sched, expiryWarningOffsetsMillis = listOf(400, 200))
        m.send(TestInvite(inviterId = a, invitedId = b, expiresAt = 1000))

        sched.advance(600)
        assertEquals(listOf("send", "warn:400"), h.events)
        sched.advance(200)
        assertEquals(listOf("send", "warn:400", "warn:200"), h.events)
        sched.advance(200)
        assertEquals(listOf("send", "warn:400", "warn:200", "expire"), h.events)
    }

    @Test fun `accepting cancels pending expiry-warning timers`() {
        val h = RecordingHandler(); val sched = FakeScheduler()
        val m = InvitationManager(h, sched, expiryWarningOffsetsMillis = listOf(400))
        val inv = TestInvite(inviterId = a, invitedId = b, expiresAt = 1000); m.send(inv)

        m.accept(inv.id)
        sched.advance(5000)

        assertEquals(listOf("send", "accept"), h.events)
    }

    @Test fun `warning offsets at or past the invite window are skipped`() {
        val h = RecordingHandler(); val sched = FakeScheduler()
        val m = InvitationManager(h, sched, expiryWarningOffsetsMillis = listOf(2000, 0, 300))
        m.send(TestInvite(inviterId = a, invitedId = b, expiresAt = 1000))

        sched.advance(700)
        assertEquals(listOf("send", "warn:300"), h.events)
        sched.advance(300)
        assertEquals(listOf("send", "warn:300", "expire"), h.events)
    }

    @Test fun `sorted queries order by createdAt`() {
        val m = InvitationManager(RecordingHandler(), FakeScheduler())
        val older = TestInvite(inviterId = a, invitedId = b, createdAt = 100)
        val newer = TestInvite(inviterId = c, invitedId = b, createdAt = 200)
        m.send(older); m.send(newer)

        assertEquals(listOf(newer, older), m.getInvitesFor(b, SortOrder.NEWEST_FIRST))
        assertEquals(listOf(older, newer), m.getInvitesFor(b, SortOrder.OLDEST_FIRST))
        assertEquals(listOf(newer, older), m.all(SortOrder.NEWEST_FIRST))
    }

    @Test fun `getInvitesBetween returns both directions`() {
        val m = InvitationManager(RecordingHandler(), FakeScheduler())
        val aToB = TestInvite(inviterId = a, invitedId = b)
        val bToA = TestInvite(inviterId = b, invitedId = a)
        m.send(aToB); m.send(bToA)

        assertEquals(setOf(aToB, bToA), m.getInvitesBetween(a, b).toSet())
        assertEquals(setOf(aToB, bToA), m.getInvitesBetween(b, a).toSet())
    }

    @Test fun `count helpers report pending sizes`() {
        val m = InvitationManager(RecordingHandler(), FakeScheduler())
        m.send(TestInvite(inviterId = a, invitedId = b))
        m.send(TestInvite(inviterId = a, invitedId = c))
        m.send(TestInvite(inviterId = c, invitedId = b))

        assertEquals(2, m.countFrom(a))
        assertEquals(2, m.countFor(b))
        assertEquals(3, m.pendingCount())
    }

    @Test fun `clearAsInviter only drops invites the player sent`() {
        val h = RecordingHandler(); val m = InvitationManager(h, FakeScheduler())
        m.send(TestInvite(inviterId = a, invitedId = b))
        m.send(TestInvite(inviterId = c, invitedId = a))

        val cleared = m.clearAsInviter(a, CancelReason.ADMIN_CLEARED)

        assertEquals(1, cleared)
        assertEquals("cancel:ADMIN_CLEARED", h.events.last())
        assertTrue(m.getInvitesFrom(a).isEmpty())
        assertEquals(1, m.getInvitesFor(a).size)
    }

    @Test fun `clearAsInvited only drops invites addressed to the player`() {
        val h = RecordingHandler(); val m = InvitationManager(h, FakeScheduler())
        m.send(TestInvite(inviterId = a, invitedId = b))
        m.send(TestInvite(inviterId = c, invitedId = a))

        val cleared = m.clearAsInvited(a)

        assertEquals(1, cleared)
        assertEquals("cancel:PARTY_CLEARED", h.events.last())
        assertTrue(m.getInvitesFor(a).isEmpty())
        assertEquals(1, m.getInvitesFrom(a).size)
    }

    @Test fun `clearAllFor passes through the given reason and clears both directions`() {
        val h = RecordingHandler(); val m = InvitationManager(h, FakeScheduler())
        m.send(TestInvite(inviterId = a, invitedId = b))
        m.send(TestInvite(inviterId = c, invitedId = a))

        val cleared = m.clearAllFor(a, CancelReason.PLAYER_QUIT)

        assertEquals(2, cleared)
        assertEquals(listOf("cancel:PLAYER_QUIT", "cancel:PLAYER_QUIT"), h.events.takeLast(2))
        assertTrue(m.all().isEmpty())
    }

    @Test fun `clearFor still works as clearAllFor with PARTY_CLEARED`() {
        val h = RecordingHandler(); val m = InvitationManager(h, FakeScheduler())
        m.send(TestInvite(inviterId = a, invitedId = b))
        m.send(TestInvite(inviterId = c, invitedId = a))

        assertEquals(2, m.clearFor(a))
        assertEquals(listOf("cancel:PARTY_CLEARED", "cancel:PARTY_CLEARED"), h.events.takeLast(2))
    }

    @Test fun `Invitations between derives expiresAt from a Duration`() {
        val inv = Invitations.between(a, b, ttl = java.time.Duration.ofSeconds(2), now = 1000)

        assertEquals(a, inv.inviterId)
        assertEquals(b, inv.invitedId)
        assertEquals(1000, inv.createdAt)
        assertEquals(3000, inv.expiresAt)
    }

    @Test fun `Invitations between with null ttl has no expiry`() {
        assertNull(Invitations.between(a, b).expiresAt)
    }

    @Test fun `requireExpiry rejects invites with no expiry`() {
        val m = InvitationManager.builder(RecordingHandler(), FakeScheduler()).requireExpiry(true).build()

        val result = m.send(TestInvite(inviterId = a, invitedId = b, expiresAt = null))

        assertEquals(InvitationManager.SendResult.ExpiryRequired, result)
        assertTrue(m.getInvitesFor(b).isEmpty())
    }

    @Test fun `requireExpiry still accepts invites that have an expiry`() {
        val m = InvitationManager.builder(RecordingHandler(), FakeScheduler()).requireExpiry(true).build()
        val inv = TestInvite(inviterId = a, invitedId = b, expiresAt = 5000)

        assertEquals(InvitationManager.SendResult.Accepted(inv.id), m.send(inv))
    }

    @Test fun `no-expiry invites are allowed by default`() {
        val m = InvitationManager(RecordingHandler(), FakeScheduler())
        val inv = TestInvite(inviterId = a, invitedId = b, expiresAt = null)

        assertEquals(InvitationManager.SendResult.Accepted(inv.id), m.send(inv))
    }

    @Test fun `maxExpiry rejects invites that outlive the guardrail`() {
        val m = InvitationManager.builder(RecordingHandler(), FakeScheduler())
            .maxExpiry(java.time.Duration.ofMillis(1000)).build()
        // FakeScheduler starts at now = 0, so this asks for a 5000ms life against a 1000ms cap.
        val inv = TestInvite(inviterId = a, invitedId = b, expiresAt = 5000)

        val result = m.send(inv)

        assertTrue(result is InvitationManager.SendResult.ExpiryTooLong)
        assertEquals(1000L, (result as InvitationManager.SendResult.ExpiryTooLong).maxMillis)
        assertEquals(5000L, result.requestedMillis)
    }

    @Test fun `maxExpiry accepts invites within the guardrail`() {
        val m = InvitationManager.builder(RecordingHandler(), FakeScheduler())
            .maxExpiry(java.time.Duration.ofMillis(5000)).build()
        val inv = TestInvite(inviterId = a, invitedId = b, expiresAt = 1000)

        assertEquals(InvitationManager.SendResult.Accepted(inv.id), m.send(inv))
    }

    @Test fun `rehydrate expires invites that lapsed during downtime`() {
        // Simulate a row persisted before a restart whose expiry passed while the server was down.
        val store = InvitationStore.InMemory<TestInvite>()
        store.save(TestInvite(inviterId = a, invitedId = b, createdAt = 0, expiresAt = 1000))

        val sched = FakeScheduler().also { it.advance(5000) } // "now" is well past expiry
        val h = RecordingHandler()
        val m = InvitationManager.builder(h, sched).store(store).build()

        val stillPending = m.rehydrate()

        assertEquals(0, stillPending)
        assertTrue(m.getInvitesFor(b).isEmpty())
        assertTrue(store.load().isEmpty())
        assertEquals(listOf("expire"), h.events)
    }

    @Test fun `sweepExpired expires live invites whose expiry has passed`() {
        // Drive the engine clock independently of the scheduler so the invite is live (its timer has
        // not fired) yet already past its expiry — the exact case a periodic sweep backstops.
        var nowMillis = 0L
        val store = InvitationStore.InMemory<TestInvite>()
        val h = RecordingHandler()
        val m = InvitationManager.builder(h, FakeScheduler()).store(store).clock { nowMillis }.build()
        val inv = TestInvite(inviterId = a, invitedId = b, expiresAt = 1000)
        m.send(inv)
        assertEquals(listOf("send"), h.events)

        nowMillis = 2000 // expiry has passed but the scheduler timer never fired
        val swept = m.sweepExpired()

        assertEquals(1, swept)
        assertTrue(m.getInvitesFor(b).isEmpty())
        assertTrue(store.load().isEmpty())
        assertEquals(listOf("send", "expire"), h.events)
    }

    @Test fun `sweepExpired leaves still-pending invites untouched`() {
        val m = InvitationManager.builder(RecordingHandler(), FakeScheduler()).build()
        val inv = TestInvite(inviterId = a, invitedId = b, expiresAt = 10_000)
        m.send(inv)

        assertEquals(0, m.sweepExpired())
        assertEquals(listOf(inv), m.getInvitesFor(b))
    }

    @Test fun `loadExpired returns only expired rows`() {
        val store = InvitationStore.InMemory<TestInvite>()
        val expired = TestInvite(inviterId = a, invitedId = b, expiresAt = 500)
        val pending = TestInvite(inviterId = a, invitedId = c, expiresAt = 5000)
        val permanent = TestInvite(inviterId = b, invitedId = c, expiresAt = null)
        store.save(expired); store.save(pending); store.save(permanent)

        assertEquals(setOf(expired.id), store.loadExpired(1000).map { it.id }.toSet())
    }
}
