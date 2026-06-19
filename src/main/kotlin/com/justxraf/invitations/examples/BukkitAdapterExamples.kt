package com.justxraf.invitations.examples

import com.justxraf.invitations.AcceptResult
import com.justxraf.invitations.BasicInvitation
import com.justxraf.invitations.CancelReason
import com.justxraf.invitations.InvitationHandler
import com.justxraf.invitations.InvitationManager
import com.justxraf.invitations.Invitations
import com.justxraf.invitations.SortOrder
import com.justxraf.invitations.bukkit.BukkitScheduler
import com.justxraf.invitations.bukkit.EventFiringObserver
import com.justxraf.invitations.bukkit.EventFiringVeto
import com.justxraf.invitations.bukkit.FoliaScheduler
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import java.time.Duration
import java.util.UUID
@Suppress("unused")
object BukkitAdapterExamples {
fun buildOnEnable(plugin: Plugin, folia: Boolean): InvitationManager<BasicInvitation> {
        val scheduler = if (folia) FoliaScheduler(plugin) else BukkitScheduler(plugin)
        return InvitationManager.builder(handler(plugin), scheduler)
            .maxPerInviter(5)
            .pairCooldownMillis(Duration.ofSeconds(30).toMillis())
            .veto(EventFiringVeto())
            .observer(EventFiringObserver())
            .build()
    }

    private fun handler(plugin: Plugin) = object : InvitationHandler<BasicInvitation> {
        override fun onSend(invitation: BasicInvitation) = tell(invitation.invitedId, "You were invited.")
        override fun onAccept(invitation: BasicInvitation) = tell(invitation.inviterId, "Invite accepted.")
        override fun onDeny(invitation: BasicInvitation) = tell(invitation.inviterId, "Invite denied.")
        override fun onCancel(invitation: BasicInvitation, reason: CancelReason) =
            tell(invitation.invitedId, "Invite cancelled ($reason).")
        override fun onExpire(invitation: BasicInvitation) = tell(invitation.invitedId, "Invite expired.")
    }

    private fun tell(playerId: UUID, message: String) {
        Bukkit.getPlayer(playerId)?.sendMessage(message)
    }
fun onEnable(manager: InvitationManager<BasicInvitation>) {
        val restored = manager.rehydrate()
        Bukkit.getLogger().info("Restored $restored pending invitations.")
    }
fun onDisable(manager: InvitationManager<BasicInvitation>) {
        manager.shutdown()
    }
class QuitListener(private val manager: InvitationManager<BasicInvitation>) : Listener {
        @EventHandler
        fun onQuit(event: PlayerQuitEvent) {
            manager.clearAllFor(event.player.uniqueId, CancelReason.PLAYER_QUIT)
        }
    }
fun onServerShutdown(manager: InvitationManager<BasicInvitation>, onlineIds: Collection<UUID>) {
        onlineIds.forEach { manager.clearAllFor(it, CancelReason.SERVER_SHUTDOWN) }
        manager.shutdown()
    }
fun onIslandDisband(manager: InvitationManager<BasicInvitation>, ownerId: UUID) {
        manager.clearAllFor(ownerId, CancelReason.PARTY_CLEARED)
    }
fun cmdInvite(manager: InvitationManager<BasicInvitation>, inviter: Player, targetName: String) {
        val target = Bukkit.getPlayerExact(targetName) ?: return inviter.sendMessage("Player not online.")
        val invite = Invitations.between(inviter.uniqueId, target.uniqueId, ttl = Duration.ofMinutes(2))
        when (val result = manager.send(invite)) {
            is InvitationManager.SendResult.Accepted -> inviter.sendMessage("Invited ${target.name}.")
            is InvitationManager.SendResult.Duplicate -> inviter.sendMessage("Already invited.")
            is InvitationManager.SendResult.LimitReached -> inviter.sendMessage("Too many pending invites.")
            is InvitationManager.SendResult.CooldownActive ->
                inviter.sendMessage("Wait ${result.remainingMillis / 1000}s before re-inviting.")
            InvitationManager.SendResult.SelfInvite -> inviter.sendMessage("You can't invite yourself.")
            InvitationManager.SendResult.Vetoed -> inviter.sendMessage("Invite blocked.")
            else -> inviter.sendMessage("Could not send invite.")
        }
    }
fun cmdAcceptLatest(manager: InvitationManager<BasicInvitation>, invited: Player) {
        val latest = manager.getMostRecentFor(invited.uniqueId)
            ?: return invited.sendMessage("You have no pending invites.")
        applyAccept(manager.acceptDetailed(latest.id), invited)
    }
fun cmdAcceptByName(manager: InvitationManager<BasicInvitation>, invited: Player, inviterName: String) {
        val inviter = Bukkit.getOfflinePlayer(inviterName).uniqueId
        applyAccept(manager.acceptDetailed(inviter, invited.uniqueId), invited)
    }

    private fun applyAccept(result: AcceptResult, invited: Player) = when (result) {
        is AcceptResult.Accepted -> invited.sendMessage("Joined.")
        AcceptResult.NotFound -> invited.sendMessage("That invite is gone.")
        AcceptResult.Vetoed -> invited.sendMessage("Couldn't accept that invite.")
    }
fun cmdDeny(manager: InvitationManager<BasicInvitation>, invited: Player, inviterName: String) {
        val inviter = Bukkit.getOfflinePlayer(inviterName).uniqueId
        val invite = manager.getInvite(inviter, invited.uniqueId)
            ?: return invited.sendMessage("No invite from $inviterName.")
        if (manager.deny(invite.id)) invited.sendMessage("Denied.")
    }
fun cmdDenyAll(manager: InvitationManager<BasicInvitation>, invited: Player) {
        invited.sendMessage("Denied ${manager.denyAll(invited.uniqueId)} invite(s).")
    }
fun cmdRevoke(manager: InvitationManager<BasicInvitation>, inviter: Player, targetName: String) {
        val target = Bukkit.getOfflinePlayer(targetName).uniqueId
        val invite = manager.getInvite(inviter.uniqueId, target)
            ?: return inviter.sendMessage("No invite to revoke.")
        if (manager.cancel(invite.id)) inviter.sendMessage("Revoked.")
    }
fun cmdRevokeAll(manager: InvitationManager<BasicInvitation>, inviter: Player) {
        inviter.sendMessage("Revoked ${manager.cancelAllFrom(inviter.uniqueId)} invite(s).")
    }
fun cmdListPending(manager: InvitationManager<BasicInvitation>, invited: Player) {
        val pending = manager.getInvitesFor(invited.uniqueId, SortOrder.NEWEST_FIRST)
        if (pending.isEmpty()) return invited.sendMessage("No pending invites.")
        pending.forEach { invited.sendMessage("From ${Bukkit.getOfflinePlayer(it.inviterId).name}") }
    }
fun cmdStats(manager: InvitationManager<BasicInvitation>, sender: CommandSender) {
        sender.sendMessage("Pending invitations: ${manager.pendingCount()} (healthy: ${manager.isHealthy()})")
    }
}
