package com.justxraf.networkapi.invitations.bukkit

import com.justxraf.networkapi.invitations.CancelReason
import com.justxraf.networkapi.invitations.Invitation
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Bukkit event classes for the invitation lifecycle. These live in the `bukkit` package so the core
 * stays server-free: nothing outside this package imports `org.bukkit.event`. A host wires them up by
 * registering [EventFiringObserver] (post-transition notifications) and/or [EventFiringVeto]
 * (pre-transition cancellation) on the manager — see those classes.
 *
 * Two families, matching the manager's two seams:
 *  - **Pre** events ([InvitationSendEvent], [InvitationAcceptEvent], [InvitationDenyEvent],
 *    [InvitationCancelEvent]) are [Cancellable] and fire *before* state mutation; cancelling one
 *    aborts the transition and the manager returns a typed `Vetoed` result.
 *  - **Post** events ([InvitationExpireEvent], [InvitationReplaceEvent]) fire *after* the transition
 *    commits and are not cancellable — the action already happened.
 *
 * The generic [Invitation] is erased at the Bukkit boundary (Bukkit's event system isn't generic);
 * listeners read the shared [Invitation] fields and cast to their concrete type if needed.
 */
sealed class InvitationEvent(val invitation: Invitation) : Event() {
    override fun getHandlers(): HandlerList = handlerListFor(this::class.java)

    companion object {
        // One HandlerList per concrete subclass, as Bukkit requires.
        private val lists = java.util.concurrent.ConcurrentHashMap<Class<*>, HandlerList>()
        internal fun handlerListFor(type: Class<*>): HandlerList = lists.getOrPut(type) { HandlerList() }
    }
}

/** Fired before an invitation is registered. Cancel to reject the send (manager returns a `Vetoed`). */
class InvitationSendEvent(invitation: Invitation) : InvitationEvent(invitation), Cancellable {
    private var cancelled = false
    override fun isCancelled() = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    companion object { @JvmStatic fun getHandlerList(): HandlerList = handlerListFor(InvitationSendEvent::class.java) }
}

/** Fired before an accept is applied. Cancel to block it (manager returns a `Vetoed`). */
class InvitationAcceptEvent(invitation: Invitation) : InvitationEvent(invitation), Cancellable {
    private var cancelled = false
    override fun isCancelled() = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    companion object { @JvmStatic fun getHandlerList(): HandlerList = handlerListFor(InvitationAcceptEvent::class.java) }
}

/** Fired before a deny is applied. Cancel to block it (manager returns a `Vetoed`). */
class InvitationDenyEvent(invitation: Invitation) : InvitationEvent(invitation), Cancellable {
    private var cancelled = false
    override fun isCancelled() = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    companion object { @JvmStatic fun getHandlerList(): HandlerList = handlerListFor(InvitationDenyEvent::class.java) }
}

/**
 * Fired before a cancellation is applied. [reason] is the [CancelReason] the manager will report.
 * Cancel to block it (manager returns a `Vetoed`). Note: only single-invite cancels ([cancel],
 * [cancelDetailed]) consult the veto; bulk clears ([clearFor], [cancelAllFrom], …) and forced reasons
 * like [CancelReason.SERVER_SHUTDOWN] are not vetoable.
 */
class InvitationCancelEvent(invitation: Invitation, val reason: CancelReason) :
    InvitationEvent(invitation), Cancellable {
    private var cancelled = false
    override fun isCancelled() = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    companion object { @JvmStatic fun getHandlerList(): HandlerList = handlerListFor(InvitationCancelEvent::class.java) }
}

/** Fired after an invitation expires. Not cancellable — expiry already happened. */
class InvitationExpireEvent(invitation: Invitation) : InvitationEvent(invitation) {
    companion object { @JvmStatic fun getHandlerList(): HandlerList = handlerListFor(InvitationExpireEvent::class.java) }
}

/**
 * Fired after a duplicate send replaced an existing invitation under
 * [com.justxraf.networkapi.invitations.DuplicatePolicy.REPLACE_EXISTING]. [invitation] is the new
 * one; [replacedId] identifies the consumed invitation. Not cancellable.
 */
class InvitationReplaceEvent(invitation: Invitation, val replacedId: java.util.UUID) :
    InvitationEvent(invitation) {
    companion object { @JvmStatic fun getHandlerList(): HandlerList = handlerListFor(InvitationReplaceEvent::class.java) }
}
