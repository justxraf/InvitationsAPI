package com.justxraf.networkapi.invitations

/**
 * The lifecycle hooks a plugin implements to give its invitations meaning (add a member, teleport,
 * send messages, persist, fire events). The [InvitationManager] owns the bookkeeping; the handler
 * owns the effect.
 *
 * Hooks run exactly once per invitation and always on the scheduler's main thread. All have empty
 * defaults, so override only what you need.
 */
interface InvitationHandler<T : Invitation> {
    /** Just registered. Notify the parties / persist. */
    fun onSend(invitation: T) {}

    /** The invited party accepted. Apply the effect. */
    fun onAccept(invitation: T) {}

    /** The invited party declined. */
    fun onDeny(invitation: T) {}

    /** Revoked before a response — [reason] says why (inviter action, player left, cleared, …). */
    fun onCancel(invitation: T, reason: CancelReason) {}

    /** [Invitation.expiresAt] was reached with no response. */
    fun onExpire(invitation: T) {}

    /**
     * Optional warning fired some time before [Invitation.expiresAt], for each configured offset (see
     * [InvitationManager.Builder.expiryWarningOffsetsMillis]). [remainingMillis] is the approximate
     * time left until expiry — use it to message the parties ("invite expires in 10s"). Never fired
     * for invitations without an [Invitation.expiresAt], nor after a terminal transition.
     */
    fun onExpiryWarning(invitation: T, remainingMillis: Long) {}

    /**
     * Guard run before [InvitationManager.send] registers an invitation. Return a non-null reason to
     * reject, or null to allow. [existing] is the live set of invites the manager already holds for
     * this inviter, so you can enforce per-player rules ("already invited", custom limits, …).
     */
    fun validate(invitation: T, existing: List<T>): String? = null
}

/** Why an invitation was cancelled, passed to [InvitationHandler.onCancel]. */
enum class CancelReason {
    /** The inviter (or an authority) explicitly revoked it. */
    REVOKED,

    /** Dropped because a party logged off / was cleared via [InvitationManager.clearFor]. */
    PARTY_CLEARED,

    /** Replaced by a newer invitation for the same inviter/invited pair. */
    DUPLICATE_REPLACED,

    /** A party disconnected; clear their invites via [InvitationManager.clearFor] with this reason. */
    PLAYER_QUIT,

    /** The server is stopping; invites cleared as part of an orderly shutdown. */
    SERVER_SHUTDOWN,

    /** Cleared to make room because the target hit a pending-invite limit. */
    TARGET_LIMIT_REACHED,

    /** An administrator (not a party to the invite) cleared it. */
    ADMIN_CLEARED,
}
