package com.justxraf.networkapi.invitations

import java.util.UUID

/**
 * Outcome of a terminal operation ([InvitationManager.acceptDetailed],
 * [InvitationManager.denyDetailed], [InvitationManager.cancelDetailed]). Richer than a bare boolean:
 * it distinguishes a successful transition from a no-op caused by an unknown or already-consumed id,
 * so retries and double-clicks are safe to reason about.
 *
 * The boolean-returning overloads ([InvitationManager.accept] etc.) remain for callers that only care
 * about success; these typed results are returned by the `*Detailed` variants.
 */
sealed interface TerminalResult {
    /** The successful transition carried the affected [invitationId]. */
    val invitationId: UUID?
        get() = null
}

/** Result of [InvitationManager.acceptDetailed]. */
sealed interface AcceptResult : TerminalResult {
    /** The invitation was accepted and [InvitationHandler.onAccept] was dispatched. */
    data class Accepted(override val invitationId: UUID) : AcceptResult
    /** The id was unknown — never sent, or already consumed by a prior terminal action or expiry. */
    data object NotFound : AcceptResult
    /** A registered [InvitationVeto] (e.g. a cancelled `InvitationAcceptEvent`) blocked the accept. */
    data object Vetoed : AcceptResult
}

/** Result of [InvitationManager.denyDetailed]. */
sealed interface DenyResult : TerminalResult {
    /** The invitation was denied and [InvitationHandler.onDeny] was dispatched. */
    data class Denied(override val invitationId: UUID) : DenyResult
    /** The id was unknown — never sent, or already consumed by a prior terminal action or expiry. */
    data object NotFound : DenyResult
    /** A registered [InvitationVeto] (e.g. a cancelled `InvitationDenyEvent`) blocked the deny. */
    data object Vetoed : DenyResult
}

/** Result of [InvitationManager.cancelDetailed]. */
sealed interface CancelResult : TerminalResult {
    /** The invitation was cancelled and [InvitationHandler.onCancel] was dispatched. */
    data class Cancelled(override val invitationId: UUID) : CancelResult
    /** The id was unknown — never sent, or already consumed by a prior terminal action or expiry. */
    data object NotFound : CancelResult
    /** A registered [InvitationVeto] (e.g. a cancelled `InvitationCancelEvent`) blocked the cancel. */
    data object Vetoed : CancelResult
}
