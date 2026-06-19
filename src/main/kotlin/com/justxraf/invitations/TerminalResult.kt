package com.justxraf.invitations

import java.util.UUID

/**
 * Common supertype of the idempotent outcomes returned by the `*Detailed` terminal operations
 * ([InvitationManager.acceptDetailed], [InvitationManager.denyDetailed],
 * [InvitationManager.cancelDetailed]). Unlike the boolean overloads these distinguish *why* an
 * operation did nothing, which lets callers reply differently to "no such invite" and "a listener
 * vetoed it".
 */
sealed interface TerminalResult {
    /** Id of the affected invitation, or `null` for outcomes that touched no specific invite. */
    val invitationId: UUID?
        get() = null
}

/** Outcome of accepting an invitation. */
sealed interface AcceptResult : TerminalResult {
    /** The invitation was accepted and removed from the pending set. */
    data class Accepted(override val invitationId: UUID) : AcceptResult
    /** No pending invitation matched the request. */
    data object NotFound : AcceptResult
    /** A pre-event listener cancelled the accept before any state changed. */
    data object Vetoed : AcceptResult
}

/** Outcome of denying an invitation. */
sealed interface DenyResult : TerminalResult {
    /** The invitation was denied and removed from the pending set. */
    data class Denied(override val invitationId: UUID) : DenyResult
    /** No pending invitation matched the request. */
    data object NotFound : DenyResult
    /** A pre-event listener cancelled the deny before any state changed. */
    data object Vetoed : DenyResult
}

/** Outcome of cancelling (revoking) an invitation. */
sealed interface CancelResult : TerminalResult {
    /** The invitation was cancelled and removed from the pending set. */
    data class Cancelled(override val invitationId: UUID) : CancelResult
    /** No pending invitation matched the request. */
    data object NotFound : CancelResult
    /** A pre-event listener cancelled the cancellation before any state changed. */
    data object Vetoed : CancelResult
}
