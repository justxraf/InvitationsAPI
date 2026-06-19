package com.justxraf.invitations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
private class TestScheduler : Scheduler {
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

private data class ObservedInvite(
    override val id: UUID = UUID.randomUUID(),
    override val inviterId: UUID,
    override val invitedId: UUID,
    override val createdAt: Long = 0,
    override val expiresAt: Long? = null,
) : Invitation

class ObservabilityTest {
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()

    private fun manager(
        block: InvitationManager.Builder<ObservedInvite>.() -> Unit = {},
        scheduler: Scheduler = TestScheduler(),
    ): InvitationManager<ObservedInvite> =
        InvitationManager.builder(object : InvitationHandler<ObservedInvite> {}, scheduler).apply(block).build()

    @Test fun `observer sees one event per lifecycle transition`() {
        val seen = mutableListOf<InvitationAction>()
        val m = manager({ observer { seen += it.action } })
        val inv = ObservedInvite(inviterId = a, invitedId = b)

        m.send(inv)
        m.accept(inv.id)

        assertEquals(listOf(InvitationAction.SENT, InvitationAction.ACCEPTED), seen)
    }

    @Test fun `observer event carries cancel reason and timestamp`() {
        var event: LifecycleEvent<ObservedInvite>? = null
        val sched = TestScheduler().also { it.advance(1000) }
        val m = manager({ observer { event = it } }, scheduler = sched)
        val inv = ObservedInvite(inviterId = a, invitedId = b)
        m.send(inv)

        m.clearAllFor(a, CancelReason.PLAYER_QUIT)

        assertEquals(InvitationAction.CANCELLED, event!!.action)
        assertEquals(CancelReason.PLAYER_QUIT, event!!.cancelReason)
        assertEquals(1000, event!!.timestamp)
    }

    @Test fun `replace fires a REPLACED event with the consumed id`() {
        val replaced = mutableListOf<UUID>()
        val m = manager({
            duplicatePolicy(DuplicatePolicy.REPLACE_EXISTING)
            observer { if (it.action == InvitationAction.REPLACED) replaced += it.replacedId!! }
        })
        val first = ObservedInvite(inviterId = a, invitedId = b)
        m.send(first)
        m.send(ObservedInvite(inviterId = a, invitedId = b))

        assertEquals(listOf(first.id), replaced)
    }

    @Test fun `veto blocks accept and yields a Vetoed result`() {
        val m = manager({ veto { _, action, _ -> action == InvitationAction.ACCEPTED } })
        val inv = ObservedInvite(inviterId = a, invitedId = b)
        m.send(inv)

        assertEquals(AcceptResult.Vetoed, m.acceptDetailed(inv.id))
        assertFalse(m.accept(inv.id))
        assertEquals(1, m.pendingCount())
    }

    @Test fun `veto blocks send before registration`() {
        val m = manager({ veto { _, action, _ -> action == InvitationAction.SENT } })

        assertEquals(InvitationManager.SendResult.Vetoed, m.send(ObservedInvite(inviterId = a, invitedId = b)))
        assertEquals(0, m.pendingCount())
    }

    @Test fun `veto sees the cancel reason`() {
        var sawReason: CancelReason? = null
        val m = manager({
            veto { _, action, reason ->
                if (action == InvitationAction.CANCELLED) sawReason = reason
                false
            }
        })
        val inv = ObservedInvite(inviterId = a, invitedId = b)
        m.send(inv)
        m.cancel(inv.id)

        assertEquals(CancelReason.REVOKED, sawReason)
    }

    @Test fun `metrics count lifecycle transitions and send outcomes`() {
        val metrics = InvitationMetrics.InMemory()
        val m = manager({ metrics(metrics) })
        val inv = ObservedInvite(inviterId = a, invitedId = b)

        m.send(inv)
        m.accept(inv.id)
        m.send(ObservedInvite(inviterId = a, invitedId = b))
        m.send(ObservedInvite(inviterId = a, invitedId = b))

        val snap = metrics.snapshot()
        assertEquals(2, snap.sent)
        assertEquals(1, snap.accepted)
        assertEquals(1, snap.duplicate)
    }

    @Test fun `metrics count store failures`() {
        val metrics = InvitationMetrics.InMemory()
        val failingStore = object : InvitationStore<ObservedInvite> {
            override fun save(invitation: ObservedInvite) = throw IllegalStateException("boom")
            override fun remove(invitationId: UUID) {}
            override fun load(): List<ObservedInvite> = emptyList()
        }
        val m = manager({ metrics(metrics); store(failingStore) })

        runCatching { m.send(ObservedInvite(inviterId = a, invitedId = b)) }

        assertEquals(1, metrics.snapshot().storeFailures)
    }

    @Test fun `audit records structured entries`() {
        val entries = mutableListOf<AuditEntry>()
        val m = manager({ audit { entries += it } })
        val inv = ObservedInvite(inviterId = a, invitedId = b, createdAt = 5)
        m.send(inv)
        m.deny(inv.id)

        assertEquals(2, entries.size)
        assertEquals(inv.id, entries[0].invitationId)
        assertEquals(InvitationAction.SENT, entries[0].action)
        assertEquals(InvitationAction.DENIED, entries[1].action)
    }

    @Test fun `ISOLATE swallows a throwing observer and still runs the others`() {
        val ran = mutableListOf<String>()
        val errors = mutableListOf<InvitationAction>()
        val m = manager({
            errorPolicy(LifecycleErrorPolicy.ISOLATE)
            errorCallback { _, action, _ -> errors += action }
            observer { throw IllegalStateException("bad observer") }
            observer { ran += "second" }
        })
        m.send(ObservedInvite(inviterId = a, invitedId = b))

        assertEquals(listOf("second"), ran)
        assertEquals(listOf(InvitationAction.SENT), errors)
    }

    @Test fun `PROPAGATE rethrows a throwing handler hook`() {
        val handler = object : InvitationHandler<ObservedInvite> {
            override fun onSend(invitation: ObservedInvite) = throw IllegalStateException("hook failed")
        }
        val m = InvitationManager.builder(handler, TestScheduler())
            .errorPolicy(LifecycleErrorPolicy.PROPAGATE)
            .build()

        val threw = runCatching { m.send(ObservedInvite(inviterId = a, invitedId = b)) }.isFailure
        assertTrue(threw)
    }

    @Test fun `logger emits a line per transition`() {
        val lines = mutableListOf<String>()
        val m = manager({ logger { _, message, _ -> lines += message } })
        val inv = ObservedInvite(inviterId = a, invitedId = b)
        m.send(inv)
        m.accept(inv.id)

        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("SENT"))
        assertTrue(lines[1].contains("ACCEPTED"))
    }

    @Test fun `noop sinks are the shared singletons`() {
        assertSame(InvitationMetrics.Noop, InvitationMetrics.Noop)
        assertSame(InvitationAudit.Noop, InvitationAudit.Noop)
    }
}
