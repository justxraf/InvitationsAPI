package com.justxraf.invitations.examples

import com.justxraf.invitations.AcceptResult
import com.justxraf.invitations.BasicInvitation
import com.justxraf.invitations.CancelReason
import com.justxraf.invitations.DuplicatePolicy
import com.justxraf.invitations.InvitationHandler
import com.justxraf.invitations.InvitationManager
import com.justxraf.invitations.Invitations
import com.justxraf.invitations.Scheduler
import com.justxraf.invitations.SelfInvitePolicy
import com.justxraf.invitations.SortOrder
import java.time.Duration
import java.util.UUID
@Suppress("unused")
object KotlinExamples {
fun buildManager(scheduler: Scheduler): InvitationManager<BasicInvitation> =
        InvitationManager.builder(handler(), scheduler)
            .maxPerInviter(5)
            .maxPerInvited(10)
            .pairCooldownMillis(Duration.ofSeconds(30).toMillis())
            .duplicatePolicy(DuplicatePolicy.REPLACE_EXISTING)
            .selfInvitePolicy(SelfInvitePolicy.REJECT)
            .expiryWarningOffsetsMillis(10_000, 5_000)
            .build()
private fun handler() = object : InvitationHandler<BasicInvitation> {
        override fun onSend(invitation: BasicInvitation) { /* message both parties */ }
        override fun onAccept(invitation: BasicInvitation) { /* apply the effect */ }
        override fun onDeny(invitation: BasicInvitation) { /* notify the inviter */ }
        override fun onCancel(invitation: BasicInvitation, reason: CancelReason) { /* notify, log reason */ }
        override fun onExpire(invitation: BasicInvitation) { /* notify it timed out */ }
        override fun onExpiryWarning(invitation: BasicInvitation, remainingMillis: Long) {
        }
        override fun validate(invitation: BasicInvitation, existing: List<BasicInvitation>): String? =
            if (existing.any { it.invitedId == invitation.invitedId }) "already.invited" else null
    }
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
            InvitationManager.SendResult.ExpiryRequired -> { /* requireExpiry is on; invite had no expiry */ }
            is InvitationManager.SendResult.ExpiryTooLong -> { /* result.maxMillis / result.requestedMillis */ }
            InvitationManager.SendResult.Vetoed -> { /* blocked by a veto / cancelled event */ }
            is InvitationManager.SendResult.StoreFailure -> { /* result.cause — persistence rejected it */ }
        }
    }
fun accept(manager: InvitationManager<BasicInvitation>, invitationId: UUID) {
        if (manager.accept(invitationId)) { /* succeeded */ }

        when (val result = manager.acceptDetailed(invitationId)) {
            is AcceptResult.Accepted -> { /* result.invitationId */ }
            AcceptResult.NotFound -> { /* unknown or already consumed — safe to ignore */ }
            AcceptResult.Vetoed -> { /* blocked by a veto / cancelled InvitationAcceptEvent */ }
        }
    }
fun acceptFromName(manager: InvitationManager<BasicInvitation>, inviter: UUID, invited: UUID) {
        manager.accept(inviter, invited)
    }
fun denyAndCancel(manager: InvitationManager<BasicInvitation>, invitationId: UUID) {
        manager.deny(invitationId)
        manager.cancel(invitationId)
    }
fun bulk(manager: InvitationManager<BasicInvitation>, player: UUID) {
        manager.denyAll(player)
        manager.cancelAllFrom(player)
    }
fun clear(manager: InvitationManager<BasicInvitation>, player: UUID) {
        manager.clearAllFor(player, CancelReason.PLAYER_QUIT)
        manager.clearAsInviter(player, CancelReason.ADMIN_CLEARED)
        manager.clearAsInvited(player)
    }
fun queries(manager: InvitationManager<BasicInvitation>, inviter: UUID, invited: UUID) {
        manager.getInvitesFor(invited, SortOrder.NEWEST_FIRST)
        manager.getInvitesFrom(inviter, SortOrder.OLDEST_FIRST)
        manager.getMostRecentFor(invited)
        manager.getInvite(inviter, invited)
        manager.getInvitesBetween(inviter, invited)
        manager.all(SortOrder.NEWEST_FIRST)

        manager.countFor(invited)
        manager.countFrom(inviter)
        manager.pendingCount()
    }
fun lifecycle(manager: InvitationManager<BasicInvitation>) {
        val stillPending = manager.rehydrate()
        check(stillPending >= 0)
        manager.shutdown()
    }
}
