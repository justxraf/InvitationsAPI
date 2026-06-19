package com.justxraf.invitations
/** The lifecycle transition an [InvitationObserver] is being notified about. */
enum class InvitationAction {
    /** A new invitation was registered. */
    SENT,
    /** An invitation was accepted. */
    ACCEPTED,
    /** An invitation was denied. */
    DENIED,
    /** An invitation was cancelled/revoked; see [LifecycleEvent.cancelReason]. */
    CANCELLED,
    /** An invitation expired. */
    EXPIRED,
    /** An invitation was replaced by a newer one; see [LifecycleEvent.replacedId]. */
    REPLACED,
}

/**
 * Immutable description of a committed lifecycle transition delivered to [InvitationObserver]s.
 *
 * @property invitation the invitation the event concerns.
 * @property action which transition occurred.
 * @property timestamp when the transition committed, in epoch millis.
 * @property cancelReason set only when [action] is [InvitationAction.CANCELLED].
 * @property replacedId id of the superseded invitation, set only for [InvitationAction.REPLACED].
 * @property actor optional authorization context for who triggered the action, when known.
 */
data class LifecycleEvent<T : Invitation>(
    val invitation: T,
    val action: InvitationAction,
    val timestamp: Long,
    val cancelReason: CancelReason? = null,
    val replacedId: java.util.UUID? = null,
    val actor: ActorContext? = null,
)
