package com.justxraf.networkapi.invitations.examples

import com.justxraf.networkapi.invitations.AcceptResult
import com.justxraf.networkapi.invitations.BasicInvitation
import com.justxraf.networkapi.invitations.CancelReason
import com.justxraf.networkapi.invitations.DuplicatePolicy
import com.justxraf.networkapi.invitations.InvitationHandler
import com.justxraf.networkapi.invitations.InvitationManager
import com.justxraf.networkapi.invitations.Invitations
import com.justxraf.networkapi.invitations.Scheduler
import com.justxraf.networkapi.invitations.SelfInvitePolicy
import com.justxraf.networkapi.invitations.SortOrder
import java.time.Duration
import java.util.UUID

/**
 * Copy-paste Kotlin examples for every primary operation of the invitations engine. These compile as
 * part of the build, so they can't drift from the API. The Java equivalents live in
 * `src/main/java/.../examples/JavaExamples.java`.
 *
 * Nothing here runs automatically; each function is a self-contained snippet you can lift into a
 * plugin. They take a [Scheduler] (use `BukkitScheduler` on a server) so they stay server-free.
 */
@Suppress("unused")
object KotlinExamples {

    /** Build a manager with the common production options. */
    fun buildManager(scheduler: Scheduler): InvitationManager<BasicInvitation> =
        InvitationManager.builder(handler(), scheduler)
            .maxPerInviter(5)
            .maxPerInvited(10)
            .pairCooldownMillis(Duration.ofSeconds(30).toMillis())
            .duplicatePolicy(DuplicatePolicy.REPLACE_EXISTING)
            .selfInvitePolicy(SelfInvitePolicy.REJECT)
            .expiryWarningOffsetsMillis(10_000, 5_000)
            .build()

    /** A handler that overrides only the hooks it needs; the rest are no-ops. */
    private fun handler() = object : InvitationHandler<BasicInvitation> {
        override fun onSend(invitation: BasicInvitation) { /* message both parties */ }
        override fun onAccept(invitation: BasicInvitation) { /* apply the effect */ }
        override fun onDeny(invitation: BasicInvitation) { /* notify the inviter */ }
        override fun onCancel(invitation: BasicInvitation, reason: CancelReason) { /* notify, log reason */ }
        override fun onExpire(invitation: BasicInvitation) { /* notify it timed out */ }
        override fun onExpiryWarning(invitation: BasicInvitation, remainingMillis: Long) {
            // "your invite expires in ${remainingMillis / 1000}s"
        }
        override fun validate(invitation: BasicInvitation, existing: List<BasicInvitation>): String? =
            if (existing.any { it.invitedId == invitation.invitedId }) "already.invited" else null
    }

    /** send — build an invitation (with a [Duration] TTL) and register it. */
    fun send(manager: InvitationManager<BasicInvitation>, inviter: UUID, invited: UUID) {
        val invite = Invitations.between(inviter, invited, ttl = Duration.ofMinutes(2))
        when (val result = manager.send(invite)) {
            is InvitationManager.SendResult.Accepted -> { /* result.invitationId */ }
            is InvitationManager.SendResult.Duplicate -> { /* result.existingId */ }
            is InvitationManager.SendResult.Replaced -> { /* result.replacedId, result.invitationId */ }
            is InvitationManager.SendResult.Refreshed -> { /* result.invitationId */ }
            is InvitationManager.SendResult.Rejected -> { /* result.reason from validate() */ }
            is InvitationManager.SendResult.PolicyRejected -> { /* result.reason: typed RejectionReason */ }
            is InvitationManager.SendResult.RateLimited -> { /* result.retryAfterMillis */ }
            is InvitationManager.SendResult.LimitReached -> { /* result.limit */ }
            is InvitationManager.SendResult.CooldownActive -> { /* result.remainingMillis */ }
            InvitationManager.SendResult.SelfInvite -> { /* inviter invited themselves */ }
            InvitationManager.SendResult.Vetoed -> { /* blocked by a veto / cancelled event */ }
            is InvitationManager.SendResult.StoreFailure -> { /* result.cause — persistence rejected it */ }
        }
    }

    /** accept by id, and the idempotent variant that survives double-clicks. */
    fun accept(manager: InvitationManager<BasicInvitation>, invitationId: UUID) {
        if (manager.accept(invitationId)) { /* succeeded */ }

        when (val result = manager.acceptDetailed(invitationId)) {
            is AcceptResult.Accepted -> { /* result.invitationId */ }
            AcceptResult.NotFound -> { /* unknown or already consumed — safe to ignore */ }
            AcceptResult.Vetoed -> { /* blocked by a veto / cancelled InvitationAcceptEvent */ }
        }
    }

    /** accept "from a name": the invited player names their inviter rather than quoting an id. */
    fun acceptFromName(manager: InvitationManager<BasicInvitation>, inviter: UUID, invited: UUID) {
        manager.accept(inviter, invited)
    }

    /** deny and cancel. */
    fun denyAndCancel(manager: InvitationManager<BasicInvitation>, invitationId: UUID) {
        manager.deny(invitationId)   // invited declines
        manager.cancel(invitationId) // inviter revokes
    }

    /** Bulk terminal operations. */
    fun bulk(manager: InvitationManager<BasicInvitation>, player: UUID) {
        manager.denyAll(player)        // decline everything addressed to player
        manager.cancelAllFrom(player)  // revoke everything player sent
    }

    /** Clearing a player's invites — directional and reason-aware. */
    fun clear(manager: InvitationManager<BasicInvitation>, player: UUID) {
        manager.clearAllFor(player, CancelReason.PLAYER_QUIT) // both directions, on disconnect
        manager.clearAsInviter(player, CancelReason.ADMIN_CLEARED)
        manager.clearAsInvited(player)
    }

    /** Queries: lookups, sorted lists, between-players, and counts. */
    fun queries(manager: InvitationManager<BasicInvitation>, inviter: UUID, invited: UUID) {
        manager.getInvitesFor(invited, SortOrder.NEWEST_FIRST)
        manager.getInvitesFrom(inviter, SortOrder.OLDEST_FIRST)
        manager.getMostRecentFor(invited)
        manager.getInvite(inviter, invited)
        manager.getInvitesBetween(inviter, invited) // either direction
        manager.all(SortOrder.NEWEST_FIRST)

        manager.countFor(invited)
        manager.countFrom(inviter)
        manager.pendingCount()
    }

    /** Lifecycle: reload from the store on enable, release timers on disable. */
    fun lifecycle(manager: InvitationManager<BasicInvitation>) {
        val stillPending = manager.rehydrate() // call once on startup, before any send
        check(stillPending >= 0)
        manager.shutdown() // call on plugin disable
    }
}
