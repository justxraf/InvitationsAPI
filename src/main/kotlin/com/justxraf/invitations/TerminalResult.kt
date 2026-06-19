package com.justxraf.invitations

import java.util.UUID
sealed interface TerminalResult {
val invitationId: UUID?
        get() = null
}
sealed interface AcceptResult : TerminalResult {
data class Accepted(override val invitationId: UUID) : AcceptResult
data object NotFound : AcceptResult
data object Vetoed : AcceptResult
}
sealed interface DenyResult : TerminalResult {
data class Denied(override val invitationId: UUID) : DenyResult
data object NotFound : DenyResult
data object Vetoed : DenyResult
}
sealed interface CancelResult : TerminalResult {
data class Cancelled(override val invitationId: UUID) : CancelResult
data object NotFound : CancelResult
data object Vetoed : CancelResult
}
