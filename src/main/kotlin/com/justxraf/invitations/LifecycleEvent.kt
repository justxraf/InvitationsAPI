package com.justxraf.invitations
enum class InvitationAction {
SENT,
ACCEPTED,
DENIED,
CANCELLED,
EXPIRED,
REPLACED,
}
data class LifecycleEvent<T : Invitation>(
    val invitation: T,
    val action: InvitationAction,
val timestamp: Long,
val cancelReason: CancelReason? = null,
val replacedId: java.util.UUID? = null,
val actor: ActorContext? = null,
)
