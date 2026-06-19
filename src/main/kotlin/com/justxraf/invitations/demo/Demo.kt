package com.justxraf.invitations.demo

import com.justxraf.invitations.CancelReason
import com.justxraf.invitations.Invitation
import com.justxraf.invitations.InvitationHandler
import com.justxraf.invitations.InvitationManager
import com.justxraf.invitations.Scheduler
import java.util.UUID

enum class IslandInviteType { MEMBER, COOP, VISIT, PVP }

data class IslandInvite(
    override val id: UUID,
    override val inviterId: UUID,
    override val invitedId: UUID,
    val inviterName: String,
    val invitedName: String,
    val islandId: Int,
    val type: IslandInviteType,
    override val createdAt: Long = System.currentTimeMillis(),
    override val expiresAt: Long? = null,
) : Invitation

class IslandInviteHandler : InvitationHandler<IslandInvite> {
    override fun validate(invitation: IslandInvite, existing: List<IslandInvite>): String? = when {
        invitation.type == IslandInviteType.MEMBER && invitation.islandId == 0 ->
            "already.a.member.of.other.island"
        else -> null
    }

    override fun onSend(i: IslandInvite) =
        println("  [island] ${i.inviterName} invited ${i.invitedName} to island ${i.islandId} as ${i.type}")
    override fun onAccept(i: IslandInvite) =
        println("  [island] ${i.invitedName} ACCEPTED — adding as ${i.type} to island ${i.islandId}")
    override fun onDeny(i: IslandInvite) =
        println("  [island] ${i.invitedName} DENIED ${i.inviterName}'s invite")
    override fun onCancel(i: IslandInvite, reason: CancelReason) =
        println("  [island] invite to ${i.invitedName} cancelled ($reason)")
    override fun onExpire(i: IslandInvite) =
        println("  [island] invite to ${i.invitedName} EXPIRED")
}

data class TeleportRequest(
    override val id: UUID,
    override val inviterId: UUID,
    override val invitedId: UUID,
    val requesterName: String,
    val receiverName: String,
    override val createdAt: Long = System.currentTimeMillis(),
    override val expiresAt: Long? = createdAt + 120_000,
) : Invitation

class TeleportHandler : InvitationHandler<TeleportRequest> {
    override fun onSend(r: TeleportRequest) =
        println("  [tpa] ${r.requesterName} -> ${r.receiverName}: request sent (use /tpaccept)")
    override fun onAccept(r: TeleportRequest) =
        println("  [tpa] ${r.receiverName} accepted — teleporting ${r.requesterName}")
    override fun onDeny(r: TeleportRequest) =
        println("  [tpa] ${r.receiverName} denied ${r.requesterName}")
    override fun onCancel(r: TeleportRequest, reason: CancelReason) =
        println("  [tpa] ${r.requesterName}'s request cancelled ($reason)")
    override fun onExpire(r: TeleportRequest) =
        println("  [tpa] request ${r.requesterName} -> ${r.receiverName} timed out")
}

private class ImmediateScheduler : Scheduler {
    private class Task(val block: () -> Unit) : Scheduler.Cancellable {
        var cancelled = false
        override fun cancel() { cancelled = true }
    }

    private val pending = mutableListOf<Task>()

    override fun runOnMainThread(block: () -> Unit) = block()

    override fun runLater(delayMillis: Long, block: () -> Unit): Scheduler.Cancellable =
        Task(block).also { pending += it }

    fun fireAllTimers() {
        val snapshot = pending.toList(); pending.clear()
        snapshot.forEach { if (!it.cancelled) it.block() }
    }
}

fun main() {
    val scheduler = ImmediateScheduler()
    val alice = UUID.randomUUID(); val bob = UUID.randomUUID(); val carol = UUID.randomUUID()

    println("=== Island invites ===")
    val islands = InvitationManager(IslandInviteHandler(), scheduler, maxPerInviter = 2)

    val r1 = islands.send(IslandInvite(UUID.randomUUID(), alice, bob, "Alice", "Bob", 42, IslandInviteType.MEMBER))
    println("  send -> $r1")
    val dup = islands.send(IslandInvite(UUID.randomUUID(), alice, bob, "Alice", "Bob", 42, IslandInviteType.MEMBER))
    println("  duplicate send -> $dup")
    println("  Bob's pending invites: ${islands.getInvitesFor(bob).map { it.type }}")
    (r1 as InvitationManager.SendResult.Accepted).let { islands.accept(it.invitationId) }

    val r2 = islands.send(IslandInvite(UUID.randomUUID(), alice, carol, "Alice", "Carol", 42, IslandInviteType.COOP))
    println("  send -> $r2")
    islands.deny((r2 as InvitationManager.SendResult.Accepted).invitationId)

    println("\n=== Per-inviter limit (maxPerInviter = 2) ===")
    repeat(3) { n ->
        val res = islands.send(IslandInvite(UUID.randomUUID(), alice, UUID.randomUUID(), "Alice", "Guest$n", 42, IslandInviteType.VISIT))
        println("  invite #$n -> $res")
    }

    println("\n=== clearFor (e.g. Alice logs off) ===")
    println("  cleared ${islands.clearFor(alice)} of Alice's invites")

    println("\n=== Teleport requests ===")
    val teleports = InvitationManager(TeleportHandler(), scheduler)
    val t1 = teleports.send(TeleportRequest(UUID.randomUUID(), bob, alice, "Bob", "Alice"))
    println("  send -> $t1")
    println("  Alice's pending tpa: ${teleports.getInvitesFor(alice).map { it.requesterName }}")

    println("\n=== Expiry (centralised timer) — fire pending timers ===")
    scheduler.fireAllTimers()
    println("  Alice's pending tpa after expiry: ${teleports.getInvitesFor(alice).size}")
}
