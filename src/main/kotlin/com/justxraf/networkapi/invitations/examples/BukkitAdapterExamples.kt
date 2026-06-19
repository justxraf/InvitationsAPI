package com.justxraf.networkapi.invitations.examples

import com.justxraf.networkapi.invitations.AcceptResult
import com.justxraf.networkapi.invitations.BasicInvitation
import com.justxraf.networkapi.invitations.CancelReason
import com.justxraf.networkapi.invitations.InvitationHandler
import com.justxraf.networkapi.invitations.InvitationManager
import com.justxraf.networkapi.invitations.Invitations
import com.justxraf.networkapi.invitations.SortOrder
import com.justxraf.networkapi.invitations.bukkit.BukkitScheduler
import com.justxraf.networkapi.invitations.bukkit.EventFiringObserver
import com.justxraf.networkapi.invitations.bukkit.EventFiringVeto
import com.justxraf.networkapi.invitations.bukkit.FoliaScheduler
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import java.time.Duration
import java.util.UUID

/**
 * Server-side wiring examples for the invitations engine: plugin lifecycle, the Folia/Bukkit
 * scheduler choice, and the common command flows (invite / accept / deny / revoke / list / stats).
 *
 * These touch `org.bukkit` types, so they live alongside the `bukkit/` adapter package and compile
 * only against the `compileOnly` Paper API — they are reference code, not part of the core. The pure,
 * server-free snippets are in [KotlinExamples]; this file is the "now run it on a server" companion.
 *
 * Nothing here executes automatically. Lift a method into your plugin and adapt the messaging.
 */
@Suppress("unused")
object BukkitAdapterExamples {

    /**
     * Build the manager during `onEnable`. Pass `folia = true` on a Folia server to get region-aware
     * scheduling; otherwise the classic Bukkit scheduler. The [EventFiringVeto]/[EventFiringObserver]
     * pair exposes the lifecycle on Bukkit's event bus so other plugins can listen and cancel.
     */
    fun buildOnEnable(plugin: Plugin, folia: Boolean): InvitationManager<BasicInvitation> {
        val scheduler = if (folia) FoliaScheduler(plugin) else BukkitScheduler(plugin)
        return InvitationManager.builder(handler(plugin), scheduler)
            .maxPerInviter(5)
            .pairCooldownMillis(Duration.ofSeconds(30).toMillis())
            .veto(EventFiringVeto())          // pre-events: send/accept/deny/cancel are cancellable
            .observer(EventFiringObserver())  // post-events: expire/replace
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

    // ---- Lifecycle integration -------------------------------------------------------------------

    /** `onEnable`: reload persisted invites once, before anything else can call [send]. */
    fun onEnable(manager: InvitationManager<BasicInvitation>) {
        val restored = manager.rehydrate()
        Bukkit.getLogger().info("Restored $restored pending invitations.")
    }

    /** `onDisable`: cancel timers and close the store. Terminal ops after this are no-ops, not errors. */
    fun onDisable(manager: InvitationManager<BasicInvitation>) {
        manager.shutdown()
    }

    /**
     * Player quit: drop both directions so a disconnected player neither holds nor blocks invites.
     * Register this [Listener] in `onEnable`.
     */
    class QuitListener(private val manager: InvitationManager<BasicInvitation>) : Listener {
        @EventHandler
        fun onQuit(event: PlayerQuitEvent) {
            manager.clearAllFor(event.player.uniqueId, CancelReason.PLAYER_QUIT)
        }
    }

    /**
     * Server shutdown is just [onDisable] with intent: prefer the dedicated reason on any manual sweep
     * you do before the store closes, so the audit log distinguishes a crash-cleanup from a quit.
     */
    fun onServerShutdown(manager: InvitationManager<BasicInvitation>, onlineIds: Collection<UUID>) {
        onlineIds.forEach { manager.clearAllFor(it, CancelReason.SERVER_SHUTDOWN) }
        manager.shutdown()
    }

    /** Island disband/delete: revoke everything the (now-gone) party owner sent or was sent. */
    fun onIslandDisband(manager: InvitationManager<BasicInvitation>, ownerId: UUID) {
        manager.clearAllFor(ownerId, CancelReason.PARTY_CLEARED)
    }

    // ---- Command flows ---------------------------------------------------------------------------

    /** `/island invite <player>` */
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

    /** `/island accept` with no argument — accept the most recent pending invite. */
    fun cmdAcceptLatest(manager: InvitationManager<BasicInvitation>, invited: Player) {
        val latest = manager.getMostRecentFor(invited.uniqueId)
            ?: return invited.sendMessage("You have no pending invites.")
        applyAccept(manager.acceptDetailed(latest.id), invited)
    }

    /** `/island accept <player>` — accept the invite from a named inviter. */
    fun cmdAcceptByName(manager: InvitationManager<BasicInvitation>, invited: Player, inviterName: String) {
        val inviter = Bukkit.getOfflinePlayer(inviterName).uniqueId
        applyAccept(manager.acceptDetailed(inviter, invited.uniqueId), invited)
    }

    private fun applyAccept(result: AcceptResult, invited: Player) = when (result) {
        is AcceptResult.Accepted -> invited.sendMessage("Joined.")
        AcceptResult.NotFound -> invited.sendMessage("That invite is gone.")
        AcceptResult.Vetoed -> invited.sendMessage("Couldn't accept that invite.")
    }

    /** `/island deny <player>` */
    fun cmdDeny(manager: InvitationManager<BasicInvitation>, invited: Player, inviterName: String) {
        val inviter = Bukkit.getOfflinePlayer(inviterName).uniqueId
        val invite = manager.getInvite(inviter, invited.uniqueId)
            ?: return invited.sendMessage("No invite from $inviterName.")
        if (manager.deny(invite.id)) invited.sendMessage("Denied.")
    }

    /** `/island denyall` */
    fun cmdDenyAll(manager: InvitationManager<BasicInvitation>, invited: Player) {
        invited.sendMessage("Denied ${manager.denyAll(invited.uniqueId)} invite(s).")
    }

    /** `/island revoke <player>` — inviter takes back a specific invite. */
    fun cmdRevoke(manager: InvitationManager<BasicInvitation>, inviter: Player, targetName: String) {
        val target = Bukkit.getOfflinePlayer(targetName).uniqueId
        val invite = manager.getInvite(inviter.uniqueId, target)
            ?: return inviter.sendMessage("No invite to revoke.")
        if (manager.cancel(invite.id)) inviter.sendMessage("Revoked.")
    }

    /** `/island revokeall` */
    fun cmdRevokeAll(manager: InvitationManager<BasicInvitation>, inviter: Player) {
        inviter.sendMessage("Revoked ${manager.cancelAllFrom(inviter.uniqueId)} invite(s).")
    }

    /** `/island invites` — list pending invites addressed to the player, newest first. */
    fun cmdListPending(manager: InvitationManager<BasicInvitation>, invited: Player) {
        val pending = manager.getInvitesFor(invited.uniqueId, SortOrder.NEWEST_FIRST)
        if (pending.isEmpty()) return invited.sendMessage("No pending invites.")
        pending.forEach { invited.sendMessage("From ${Bukkit.getOfflinePlayer(it.inviterId).name}") }
    }

    /** `/island invitestats` — quick operator-facing counts. */
    fun cmdStats(manager: InvitationManager<BasicInvitation>, sender: CommandSender) {
        sender.sendMessage("Pending invitations: ${manager.pendingCount()} (healthy: ${manager.isHealthy()})")
    }
}
