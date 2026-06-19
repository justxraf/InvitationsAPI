package com.justxraf.invitations
/**
 * Lifecycle hooks invoked by [InvitationManager] as invitations move through their states. All hooks
 * have no-op defaults, so callers override only what they need; Java callers may prefer
 * [JavaInvitationHandler].
 *
 * The `on*` hooks fire **after** the corresponding state mutation has committed, dispatched on the
 * main thread and never while the manager lock is held (see [InvitationManager]'s concurrency
 * contract). [validate] is the one exception: it runs *before* mutation and may reject a send.
 */
interface InvitationHandler<T : Invitation> {
    /** Fired after an invitation has been successfully registered. */
    fun onSend(invitation: T) {}
    /** Fired after an invitation has been accepted and removed. */
    fun onAccept(invitation: T) {}
    /** Fired after an invitation has been denied and removed. */
    fun onDeny(invitation: T) {}
    /** Fired after an invitation has been cancelled/revoked, with the categorized [reason]. */
    fun onCancel(invitation: T, reason: CancelReason) {}
    /** Fired after an invitation has expired and been removed. */
    fun onExpire(invitation: T) {}
    /** Fired when an invitation is approaching expiry, at each configured warning offset. */
    fun onExpiryWarning(invitation: T, remainingMillis: Long) {}

    /**
     * Advisory pre-send validation. Return a non-null string to reject the send with that message;
     * return `null` to allow it. Runs outside the manager lock (so it may block on name/permission
     * lookups), which is why the authoritative duplicate/cap/cooldown checks are re-evaluated in-lock.
     *
     * @param existing the invitations currently visible for the same pair at call time (a snapshot).
     */
    fun validate(invitation: T, existing: List<T>): String? = null
}

/** Why an invitation was cancelled, passed to [InvitationHandler.onCancel]. */
enum class CancelReason {
    /** Explicitly revoked by the inviter or a direct cancel call. */
    REVOKED,
    /** The owning party/group was cleared. Also the alias reason used by `clearFor`. */
    PARTY_CLEARED,
    /** Superseded by a newer invitation under `DuplicatePolicy.REPLACE_EXISTING`. */
    DUPLICATE_REPLACED,
    /** A participating player quit the server. */
    PLAYER_QUIT,
    /** Cancelled as part of a server shutdown. */
    SERVER_SHUTDOWN,
    /** Cancelled to make room when the invited player's pending-invite cap was reached. */
    TARGET_LIMIT_REACHED,
    /** Cleared by an administrative action. */
    ADMIN_CLEARED,
}
