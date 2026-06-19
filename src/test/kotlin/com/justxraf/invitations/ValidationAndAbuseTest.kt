package com.justxraf.invitations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
private class ClockScheduler : Scheduler {
    private class Task(val fireAt: Long, val block: () -> Unit) : Scheduler.Cancellable {
        var cancelled = false
        override fun cancel() { cancelled = true }
    }
    private val tasks = mutableListOf<Task>()
    var clock = 0L
    override fun now(): Long = clock
    override fun runOnMainThread(block: () -> Unit) = block()
    override fun runLater(delayMillis: Long, block: () -> Unit): Scheduler.Cancellable =
        Task(clock + delayMillis, block).also { tasks += it }
    fun advance(millis: Long) {
        clock += millis
        tasks.filter { !it.cancelled && it.fireAt <= clock }.also { tasks.removeAll(it.toSet()) }.forEach { it.block() }
    }
}

private data class Invite(
    override val id: UUID = UUID.randomUUID(),
    override val inviterId: UUID,
    override val invitedId: UUID,
    override val createdAt: Long = 0,
    override val expiresAt: Long? = null,
) : Invitation

class ValidationAndAbuseTest {
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()
    private val handler = object : InvitationHandler<Invite> {}

    private fun manager(
        scheduler: Scheduler = ClockScheduler(),
        build: InvitationManager.Builder<Invite>.() -> Unit = {},
    ): InvitationManager<Invite> =
        InvitationManager.builder(handler, scheduler).selfInvitePolicy(SelfInvitePolicy.ALLOW).apply(build).build()

    @Test fun `rejection reason renders fallback with args`() {
        val r = RejectionReason(
            RejectionReason.Code.PARTY_FULL, "k", "Full ({current}/{max}).",
            mapOf("current" to "4", "max" to "4"),
        )
        assertEquals("Full (4/4).", r.renderFallback())
    }

    @Test fun `self-invite policy rejects with typed reason`() {
        val m = manager { validationPolicy(ValidationPolicy.selfInvite()) }
        val r = m.send(Invite(inviterId = a, invitedId = a))
        val rejected = assertInstanceOf(InvitationManager.SendResult.PolicyRejected::class.java, r)
        assertEquals(RejectionReason.Code.SELF_INVITE, rejected.reason.code)
    }

    @Test fun `target online policy blocks offline targets but admin bypasses`() {
        val online = mutableSetOf(a)
        val m = manager { validationPolicy(ValidationPolicy.targetOnline { it in online }) }
        assertInstanceOf(
            InvitationManager.SendResult.PolicyRejected::class.java,
            m.send(Invite(inviterId = a, invitedId = b)),
        )
        assertInstanceOf(
            InvitationManager.SendResult.Accepted::class.java,
            m.send(Invite(inviterId = a, invitedId = b), ActorContext.ADMIN),
        )
    }

    @Test fun `ignore-list policy blocks invites from ignored inviter`() {
        val m = manager {
            validationPolicy(ValidationPolicy.targetNotIgnoring { who, other -> who == b && other == a })
        }
        val r = m.send(Invite(inviterId = a, invitedId = b))
        assertEquals(
            RejectionReason.Code.TARGET_IGNORING_INVITER,
            (r as InvitationManager.SendResult.PolicyRejected).reason.code,
        )
    }

    @Test fun `party full policy rejects with current and max args`() {
        val m = manager {
            validationPolicy(ValidationPolicy.partyNotFull { ValidationPolicy.PartyCapacity.Capacity(4, 4) })
        }
        val r = m.send(Invite(inviterId = a, invitedId = b)) as InvitationManager.SendResult.PolicyRejected
        assertEquals(RejectionReason.Code.PARTY_FULL, r.reason.code)
        assertEquals("4", r.reason.args["max"])
    }

    @Test fun `same-party policy rejects with typed reason`() {
        val m = manager {
            validationPolicy(ValidationPolicy.notAlreadyInSameParty { x, y -> x == a && y == b })
        }

        val r = m.send(Invite(inviterId = a, invitedId = b)) as InvitationManager.SendResult.PolicyRejected

        assertEquals(RejectionReason.Code.ALREADY_IN_SAME_PARTY, r.reason.code)
    }

    @Test fun `permission policy uses actor context`() {
        val node = "invites.send"
        val perms = ActorContext.PermissionChecker { id, n -> id == a && n == node }
        val m = manager { validationPolicy(ValidationPolicy.inviterHasPermission(node, allowAdminBypass = false)) }
        assertInstanceOf(
            InvitationManager.SendResult.Accepted::class.java,
            m.send(Invite(inviterId = a, invitedId = b), ActorContext(actorId = a, permissions = perms)),
        )
        val r = m.send(Invite(inviterId = b, invitedId = a), ActorContext(actorId = b, permissions = perms))
        assertEquals(
            RejectionReason.Code.INVITER_LACKS_PERMISSION,
            (r as InvitationManager.SendResult.PolicyRejected).reason.code,
        )
    }

    @Test fun `invited permission and world restriction policies reject with typed reasons`() {
        val invitedNode = "invites.receive"
        val allowedWorld = UUID.randomUUID()
        val perms = ActorContext.PermissionChecker { id, node -> id == b && node == invitedNode }
        val worldBlocked = manager {
            validationPolicy(ValidationPolicy.invitedHasPermission(invitedNode, perms))
            validationPolicy(ValidationPolicy.worldOrServerRestriction(allowedWorlds = setOf(allowedWorld)))
        }

        val worldRejection = worldBlocked.send(
            Invite(inviterId = a, invitedId = b),
            ActorContext(actorId = a, worldId = UUID.randomUUID()),
        ) as InvitationManager.SendResult.PolicyRejected
        assertEquals(RejectionReason.Code.WORLD_OR_SERVER_RESTRICTED, worldRejection.reason.code)

        val permissionBlocked = manager {
            validationPolicy(ValidationPolicy.invitedHasPermission(invitedNode, ActorContext.PermissionChecker.NONE))
        }
        val permissionRejection = permissionBlocked.send(Invite(inviterId = a, invitedId = b))
            as InvitationManager.SendResult.PolicyRejected
        assertEquals(RejectionReason.Code.INVITED_LACKS_PERMISSION, permissionRejection.reason.code)
    }

    @Test fun `policies run in order and first rejection wins`() {
        val m = manager {
            validationPolicy(ValidationPolicy.selfInvite())
            validationPolicy { _, _, _ -> RejectionReason.custom("k", "second") }
        }
        val r = m.send(Invite(inviterId = a, invitedId = a)) as InvitationManager.SendResult.PolicyRejected
        assertEquals(RejectionReason.Code.SELF_INVITE, r.reason.code)
    }

    @Test fun `per-inviter rate limit blocks the over-limit send and reports retry`() {
        val sched = ClockScheduler()
        val m = manager(sched) {
            rateLimits(perInviter = RateLimiter.Limit(max = 2, windowMillis = 1000))
        }
        assertInstanceOf(InvitationManager.SendResult.Accepted::class.java, m.send(Invite(inviterId = a, invitedId = b)))
        val c = UUID.randomUUID()
        assertInstanceOf(InvitationManager.SendResult.Accepted::class.java, m.send(Invite(inviterId = a, invitedId = c)))
        val third = m.send(Invite(inviterId = a, invitedId = UUID.randomUUID()))
        val limited = assertInstanceOf(InvitationManager.SendResult.RateLimited::class.java, third)
        assertEquals(1000, limited.retryAfterMillis)
        sched.advance(1000)
        assertInstanceOf(
            InvitationManager.SendResult.Accepted::class.java,
            m.send(Invite(inviterId = a, invitedId = UUID.randomUUID())),
        )
    }

    @Test fun `per-invited and per-pair rate limits block independently`() {
        val invitedLimited = manager {
            rateLimits(perInvited = RateLimiter.Limit(max = 1, windowMillis = 1000))
        }
        assertInstanceOf(InvitationManager.SendResult.Accepted::class.java, invitedLimited.send(Invite(inviterId = a, invitedId = b)))
        assertInstanceOf(
            InvitationManager.SendResult.RateLimited::class.java,
            invitedLimited.send(Invite(inviterId = UUID.randomUUID(), invitedId = b)),
        )

        val pairLimited = manager {
            duplicatePolicy(DuplicatePolicy.REPLACE_EXISTING)
            rateLimits(perPair = RateLimiter.Limit(max = 1, windowMillis = 1000))
        }
        assertInstanceOf(InvitationManager.SendResult.Accepted::class.java, pairLimited.send(Invite(inviterId = a, invitedId = b)))
        assertInstanceOf(
            InvitationManager.SendResult.RateLimited::class.java,
            pairLimited.send(Invite(inviterId = a, invitedId = b)),
        )
    }

    @Test fun `admin send bypasses the rate limiter`() {
        val m = manager { rateLimits(perInviter = RateLimiter.Limit(max = 1, windowMillis = 1000)) }
        m.send(Invite(inviterId = a, invitedId = b))
        assertInstanceOf(
            InvitationManager.SendResult.Accepted::class.java,
            m.send(Invite(inviterId = a, invitedId = UUID.randomUUID()), ActorContext.ADMIN),
        )
    }

    @Test fun `rate limiter noop when no dimensions configured`() {
        assertTrue(RateLimiter { 0 }.isNoop())
    }

    @Test fun `actor context reaches send and terminal audit entries`() {
        val entries = mutableListOf<AuditEntry>()
        val m = manager { audit { entries += it } }
        val worldId = UUID.randomUUID()
        val sentBy = ActorContext(actorId = a, worldId = worldId, serverId = "hub")
        val acceptedBy = ActorContext(actorId = b, serverId = "islands")
        val inv = Invite(inviterId = a, invitedId = b)

        m.send(inv, sentBy)
        m.acceptDetailed(inv.id, acceptedBy)

        assertEquals(a, entries[0].actorId)
        assertEquals(worldId, entries[0].actorWorldId)
        assertEquals("hub", entries[0].actorServerId)
        assertEquals(b, entries[1].actorId)
        assertEquals("islands", entries[1].actorServerId)
    }

    @Test fun `adminCancel bypasses a veto that blocks normal cancel`() {
        val veto = InvitationVeto<Invite> { _, action, _ -> action == InvitationAction.CANCELLED }
        val m = manager { veto(veto) }
        val sent = m.send(Invite(inviterId = a, invitedId = b)) as InvitationManager.SendResult.Accepted
        assertSame(CancelResult.Vetoed, m.cancelDetailed(sent.invitationId))
        assertTrue(m[sent.invitationId] != null)
        assertInstanceOf(CancelResult.Cancelled::class.java, m.adminCancel(sent.invitationId))
        assertNull(m[sent.invitationId])
    }

    @Test fun `adminCancel records admin actor in audit`() {
        val entries = mutableListOf<AuditEntry>()
        val mod = UUID.randomUUID()
        val m = manager { audit { entries += it } }
        val sent = m.send(Invite(inviterId = a, invitedId = b)) as InvitationManager.SendResult.Accepted

        m.adminCancel(sent.invitationId, ActorContext(actorId = mod, serverId = "staff", admin = true))

        val cancelled = entries.single { it.action == InvitationAction.CANCELLED }
        assertEquals(CancelReason.ADMIN_CLEARED, cancelled.cancelReason)
        assertEquals(mod, cancelled.actorId)
        assertEquals("staff", cancelled.actorServerId)
        assertTrue(cancelled.actorAdmin)
    }

    @Test fun `adminClearAllFor clears both directions with ADMIN_CLEARED`() {
        var lastReason: CancelReason? = null
        val h = object : InvitationHandler<Invite> {
            override fun onCancel(invitation: Invite, reason: CancelReason) { lastReason = reason }
        }
        val m = InvitationManager.builder(h, ClockScheduler()).selfInvitePolicy(SelfInvitePolicy.ALLOW).build()
        m.send(Invite(inviterId = a, invitedId = b))
        m.send(Invite(inviterId = b, invitedId = a))
        assertEquals(2, m.adminClearAllFor(a))
        assertEquals(0, m.pendingCount())
        assertEquals(CancelReason.ADMIN_CLEARED, lastReason)
    }

    @Test fun `legacy validate still rejects after policies pass`() {
        val h = object : InvitationHandler<Invite> {
            override fun validate(invitation: Invite, existing: List<Invite>): String? = "nope"
        }
        val m = InvitationManager.builder(h, ClockScheduler()).selfInvitePolicy(SelfInvitePolicy.ALLOW).build()
        val r = m.send(Invite(inviterId = a, invitedId = b))
        assertEquals("nope", (r as InvitationManager.SendResult.Rejected).reason)
    }
}
