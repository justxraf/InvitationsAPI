package com.justxraf.networkapi.invitations.bukkit

import com.justxraf.networkapi.invitations.CancelReason
import com.justxraf.networkapi.invitations.Invitation
import com.justxraf.networkapi.invitations.InvitationAction
import com.justxraf.networkapi.invitations.InvitationObserver
import com.justxraf.networkapi.invitations.InvitationVeto
import com.justxraf.networkapi.invitations.LifecycleEvent
import org.bukkit.Bukkit

/**
 * Bridges the manager's post-transition [InvitationObserver] seam onto Bukkit's event bus: each
 * observed [LifecycleEvent] is turned into the matching *post* Bukkit event ([InvitationExpireEvent],
 * [InvitationReplaceEvent]) and fired on the server. Register it on the manager's builder
 * (`observer(EventFiringObserver())`) to let other plugins listen for invitation outcomes.
 *
 * Only [InvitationAction.EXPIRED] and [InvitationAction.REPLACED] have post events — the cancellable
 * pre events (send/accept/deny/cancel) are fired by [EventFiringVeto] *before* mutation instead, so
 * firing them again here would be a duplicate. Must run on the main thread; the manager already
 * dispatches observers there via [com.justxraf.networkapi.invitations.Scheduler.runOnMainThread].
 */
class EventFiringObserver<T : Invitation> : InvitationObserver<T> {
    override fun onEvent(event: LifecycleEvent<T>) {
        val bukkitEvent = when (event.action) {
            InvitationAction.EXPIRED -> InvitationExpireEvent(event.invitation)
            InvitationAction.REPLACED ->
                event.replacedId?.let { InvitationReplaceEvent(event.invitation, it) }
            // send/accept/deny/cancel are handled as cancellable pre-events by EventFiringVeto.
            else -> null
        } ?: return
        Bukkit.getPluginManager().callEvent(bukkitEvent)
    }
}

/**
 * Bridges the manager's pre-transition [InvitationVeto] seam onto Bukkit's *cancellable* events: for
 * send/accept/deny/cancel it fires the matching event and reports `isCancelled` back to the manager,
 * which then aborts the transition and returns a typed `Vetoed` result. Register it on the builder
 * (`veto(EventFiringVeto())`).
 *
 * Runs on the main thread (the manager calls vetoes inline, before mutation, on the same thread that
 * invoked the operation — callers must invoke from the main thread for this to be correct).
 */
class EventFiringVeto<T : Invitation> : InvitationVeto<T> {
    override fun isVetoed(invitation: T, action: InvitationAction, cancelReason: CancelReason?): Boolean {
        val event = when (action) {
            InvitationAction.SENT -> InvitationSendEvent(invitation)
            InvitationAction.ACCEPTED -> InvitationAcceptEvent(invitation)
            InvitationAction.DENIED -> InvitationDenyEvent(invitation)
            InvitationAction.CANCELLED ->
                InvitationCancelEvent(invitation, cancelReason ?: CancelReason.REVOKED)
            // EXPIRED / REPLACED are post-only, never vetoed.
            else -> return false
        }
        Bukkit.getPluginManager().callEvent(event)
        return event.isCancelled
    }
}
