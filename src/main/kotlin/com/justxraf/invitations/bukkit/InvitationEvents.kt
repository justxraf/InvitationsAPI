package com.justxraf.invitations.bukkit

import com.justxraf.invitations.CancelReason
import com.justxraf.invitations.Invitation
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
/**
 * Base class for the Bukkit events fired by the invitation adapters. These live in the `bukkit/`
 * package so the core stays server-free; they are bridged onto the core veto/observer seams by
 * `EventFiringVeto`/`EventFiringObserver`. The pre-action events ([InvitationSendEvent],
 * [InvitationAcceptEvent], [InvitationDenyEvent], [InvitationCancelEvent]) are `Cancellable` and run
 * before any state changes; [InvitationExpireEvent]/[InvitationReplaceEvent] are post-only.
 */
sealed class InvitationEvent(val invitation: Invitation) : Event() {
    override fun getHandlers(): HandlerList = handlerListFor(this::class.java)

    companion object {
        private val lists = java.util.concurrent.ConcurrentHashMap<Class<*>, HandlerList>()
        internal fun handlerListFor(type: Class<*>): HandlerList = lists.getOrPut(type) { HandlerList() }
    }
}
/** Cancellable pre-event fired before an invitation is registered. Cancelling yields `SendResult.Vetoed`. */
class InvitationSendEvent(invitation: Invitation) : InvitationEvent(invitation), Cancellable {
    private var cancelled = false
    override fun isCancelled() = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    companion object { @JvmStatic fun getHandlerList(): HandlerList = handlerListFor(InvitationSendEvent::class.java) }
}
/** Cancellable pre-event fired before an invitation is accepted. Cancelling yields `AcceptResult.Vetoed`. */
class InvitationAcceptEvent(invitation: Invitation) : InvitationEvent(invitation), Cancellable {
    private var cancelled = false
    override fun isCancelled() = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    companion object { @JvmStatic fun getHandlerList(): HandlerList = handlerListFor(InvitationAcceptEvent::class.java) }
}
/** Cancellable pre-event fired before an invitation is denied. Cancelling yields `DenyResult.Vetoed`. */
class InvitationDenyEvent(invitation: Invitation) : InvitationEvent(invitation), Cancellable {
    private var cancelled = false
    override fun isCancelled() = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    companion object { @JvmStatic fun getHandlerList(): HandlerList = handlerListFor(InvitationDenyEvent::class.java) }
}
/**
 * Cancellable pre-event fired before an invitation is cancelled. Cancelling yields
 * `CancelResult.Vetoed`. [reason] is the categorized cancellation reason.
 */
class InvitationCancelEvent(invitation: Invitation, val reason: CancelReason) :
    InvitationEvent(invitation), Cancellable {
    private var cancelled = false
    override fun isCancelled() = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    companion object { @JvmStatic fun getHandlerList(): HandlerList = handlerListFor(InvitationCancelEvent::class.java) }
}
/** Post-only event fired after an invitation has expired. Not cancellable. */
class InvitationExpireEvent(invitation: Invitation) : InvitationEvent(invitation) {
    companion object { @JvmStatic fun getHandlerList(): HandlerList = handlerListFor(InvitationExpireEvent::class.java) }
}
/**
 * Post-only event fired after an invitation replaced an older one under `REPLACE_EXISTING`.
 * [replacedId] is the id of the superseded invitation. Not cancellable.
 */
class InvitationReplaceEvent(invitation: Invitation, val replacedId: java.util.UUID) :
    InvitationEvent(invitation) {
    companion object { @JvmStatic fun getHandlerList(): HandlerList = handlerListFor(InvitationReplaceEvent::class.java) }
}
