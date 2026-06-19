package com.justxraf.invitations

import java.util.UUID
/**
 * Audit sink for production debugging and compliance: a flat record of who invited whom, the outcome,
 * reason, and timestamps. Wired through [AuditObserver]; persist entries to a log, table, or SIEM.
 */
fun interface InvitationAudit {
    /** Persist a single audit record. */
    fun record(entry: AuditEntry)

    companion object {
        /** Audit sink that discards everything. The default. */
        @JvmField
        val Noop: InvitationAudit = InvitationAudit { }
    }
}

/**
 * Flattened, self-contained audit record for one lifecycle transition. Unlike [LifecycleEvent] it
 * carries no generic payload and inlines the [ActorContext] fields, so it serializes trivially. Build
 * one from an event with [from].
 */
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
        /** Flatten a [LifecycleEvent] (and its optional actor) into an [AuditEntry]. */
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
/** [InvitationObserver] that converts each lifecycle event to an [AuditEntry] and records it. */
class AuditObserver<T : Invitation>(private val audit: InvitationAudit) : InvitationObserver<T> {
    override fun onEvent(event: LifecycleEvent<T>) = audit.record(AuditEntry.from(event))
}
