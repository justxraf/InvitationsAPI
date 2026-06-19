package com.justxraf.invitations.bukkit

import com.justxraf.invitations.CancelReason
import com.justxraf.invitations.Invitation
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
sealed class InvitationEvent(val invitation: Invitation) : Event() {
    override fun getHandlers(): HandlerList = handlerListFor(this::class.java)

    companion object {
        private val lists = java.util.concurrent.ConcurrentHashMap<Class<*>, HandlerList>()
        internal fun handlerListFor(type: Class<*>): HandlerList = lists.getOrPut(type) { HandlerList() }
    }
}
class InvitationSendEvent(invitation: Invitation) : InvitationEvent(invitation), Cancellable {
    private var cancelled = false
    override fun isCancelled() = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    companion object { @JvmStatic fun getHandlerList(): HandlerList = handlerListFor(InvitationSendEvent::class.java) }
}
class InvitationAcceptEvent(invitation: Invitation) : InvitationEvent(invitation), Cancellable {
    private var cancelled = false
    override fun isCancelled() = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    companion object { @JvmStatic fun getHandlerList(): HandlerList = handlerListFor(InvitationAcceptEvent::class.java) }
}
class InvitationDenyEvent(invitation: Invitation) : InvitationEvent(invitation), Cancellable {
    private var cancelled = false
    override fun isCancelled() = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    companion object { @JvmStatic fun getHandlerList(): HandlerList = handlerListFor(InvitationDenyEvent::class.java) }
}
class InvitationCancelEvent(invitation: Invitation, val reason: CancelReason) :
    InvitationEvent(invitation), Cancellable {
    private var cancelled = false
    override fun isCancelled() = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    companion object { @JvmStatic fun getHandlerList(): HandlerList = handlerListFor(InvitationCancelEvent::class.java) }
}
class InvitationExpireEvent(invitation: Invitation) : InvitationEvent(invitation) {
    companion object { @JvmStatic fun getHandlerList(): HandlerList = handlerListFor(InvitationExpireEvent::class.java) }
}
class InvitationReplaceEvent(invitation: Invitation, val replacedId: java.util.UUID) :
    InvitationEvent(invitation) {
    companion object { @JvmStatic fun getHandlerList(): HandlerList = handlerListFor(InvitationReplaceEvent::class.java) }
}
