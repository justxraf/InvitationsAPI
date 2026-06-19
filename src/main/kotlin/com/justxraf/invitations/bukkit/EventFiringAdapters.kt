package com.justxraf.invitations.bukkit

import com.justxraf.invitations.CancelReason
import com.justxraf.invitations.Invitation
import com.justxraf.invitations.InvitationAction
import com.justxraf.invitations.InvitationObserver
import com.justxraf.invitations.InvitationVeto
import com.justxraf.invitations.LifecycleEvent
import org.bukkit.Bukkit
/**
 * [InvitationObserver] that fires the post-only Bukkit events ([InvitationExpireEvent],
 * [InvitationReplaceEvent]) for transitions that have no cancellable pre-event. Register it on the
 * manager alongside [EventFiringVeto].
 */
class EventFiringObserver<T : Invitation> : InvitationObserver<T> {
    override fun onEvent(event: LifecycleEvent<T>) {
        val bukkitEvent = when (event.action) {
            InvitationAction.EXPIRED -> InvitationExpireEvent(event.invitation)
            InvitationAction.REPLACED ->
                event.replacedId?.let { InvitationReplaceEvent(event.invitation, it) }
            else -> null
        } ?: return
        Bukkit.getPluginManager().callEvent(bukkitEvent)
    }
}
/**
 * [InvitationVeto] that fires a cancellable Bukkit pre-event for each vetoable action and reports the
 * event's `isCancelled` back to the manager, turning a cancelled event into a `*.Vetoed` result.
 */
class EventFiringVeto<T : Invitation> : InvitationVeto<T> {
    override fun isVetoed(invitation: T, action: InvitationAction, cancelReason: CancelReason?): Boolean {
        val event = when (action) {
            InvitationAction.SENT -> InvitationSendEvent(invitation)
            InvitationAction.ACCEPTED -> InvitationAcceptEvent(invitation)
            InvitationAction.DENIED -> InvitationDenyEvent(invitation)
            InvitationAction.CANCELLED ->
                InvitationCancelEvent(invitation, cancelReason ?: CancelReason.REVOKED)
            else -> return false
        }
        Bukkit.getPluginManager().callEvent(event)
        return event.isCancelled
    }
}
