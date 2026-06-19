package com.justxraf.networkapi.invitations

/**
 * The set of lifecycle transitions an invitation can undergo, reported to [InvitationObserver]s,
 * metrics, logging, and audit sinks. This is a *post-transition* notification vocabulary: by the
 * time an action appears here the manager's in-memory state and [InvitationStore] already reflect it.
 *
 * Distinct from [InvitationHandler], which owns the *effect* of a transition (add a member, teleport).
 * Observers are passive: they watch, count, log, and audit — they never change manager state and
 * cannot veto. Vetoing is the job of [InvitationHandler.validate] and (on Bukkit) cancellable events.
 */
enum class InvitationAction {
    /** A new invitation was registered (after [InvitationHandler.onSend]'s precondition checks). */
    SENT,

    /** The invited party accepted. */
    ACCEPTED,

    /** The invited party declined. */
    DENIED,

    /** Revoked before a response; pair with [LifecycleEvent.cancelReason] for the why. */
    CANCELLED,

    /** Reached [Invitation.expiresAt] with no response. */
    EXPIRED,

    /**
     * A duplicate (inviter, invited) send consumed an existing invitation and registered a new one
     * under [DuplicatePolicy.REPLACE_EXISTING]. [LifecycleEvent.replacedId] carries the consumed id.
     */
    REPLACED,
}

/**
 * One immutable record of a lifecycle transition, handed to every [InvitationObserver] (and to the
 * built-in logging / metrics / audit observers). Carries everything a sink needs without reaching
 * back into the manager: the [invitation] involved, the [action], the [CancelReason] when the action
 * is [InvitationAction.CANCELLED], the consumed id when it is [InvitationAction.REPLACED], and a
 * timestamp drawn from the scheduler's clock so audit lines line up with expiry decisions.
 */
data class LifecycleEvent<T : Invitation>(
    val invitation: T,
    val action: InvitationAction,
    /** epoch millis, from [Scheduler.now]. */
    val timestamp: Long,
    /** Non-null only when [action] is [InvitationAction.CANCELLED]. */
    val cancelReason: CancelReason? = null,
    /** Non-null only when [action] is [InvitationAction.REPLACED]: the id of the consumed invitation. */
    val replacedId: java.util.UUID? = null,
)
