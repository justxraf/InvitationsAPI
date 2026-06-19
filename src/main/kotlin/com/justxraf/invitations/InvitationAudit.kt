package com.justxraf.invitations

import java.util.UUID
fun interface InvitationAudit {
    fun record(entry: AuditEntry)

    companion object {
@JvmField
        val Noop: InvitationAudit = InvitationAudit { }
    }
}
data class AuditEntry(
    val invitationId: UUID,
    val inviterId: UUID,
    val invitedId: UUID,
    val action: InvitationAction,
val timestamp: Long,
val createdAt: Long,
val expiresAt: Long?,
    val cancelReason: CancelReason? = null,
    val replacedId: UUID? = null,
    val actorId: UUID? = null,
    val actorAdmin: Boolean = false,
    val actorWorldId: UUID? = null,
    val actorServerId: String? = null,
) {
    companion object {
fun <T : Invitation> from(event: LifecycleEvent<T>): AuditEntry = AuditEntry(
            invitationId = event.invitation.id,
            inviterId = event.invitation.inviterId,
            invitedId = event.invitation.invitedId,
            action = event.action,
            timestamp = event.timestamp,
            createdAt = event.invitation.createdAt,
            expiresAt = event.invitation.expiresAt,
            cancelReason = event.cancelReason,
            replacedId = event.replacedId,
            actorId = event.actor?.actorId,
            actorAdmin = event.actor?.admin == true,
            actorWorldId = event.actor?.worldId,
            actorServerId = event.actor?.serverId,
        )
    }
}
class AuditObserver<T : Invitation>(private val audit: InvitationAudit) : InvitationObserver<T> {
    override fun onEvent(event: LifecycleEvent<T>) = audit.record(AuditEntry.from(event))
}
