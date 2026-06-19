package com.justxraf.networkapi.invitations

import java.util.UUID

/**
 * An optional audit sink for production debugging — the "who invited whom, what happened, when, and
 * why" trail. Distinct from [InvitationMetrics] (aggregate counters) and [InvitationLogger] (free-form
 * lines): an audit sink receives one structured [AuditEntry] per lifecycle transition that a host can
 * persist to a table, ship to a log pipeline, or expose to staff. Defaults to [Noop].
 *
 * Wired as a built-in [InvitationObserver] ([AuditObserver]) when configured, so entries arrive on the
 * scheduler's main thread and only for transitions that actually happened. Exceptions thrown here are
 * isolated per the manager's [LifecycleErrorPolicy].
 */
fun interface InvitationAudit {
    fun record(entry: AuditEntry)

    companion object {
        /** Discards every entry. The manager's default. */
        @JvmField
        val Noop: InvitationAudit = InvitationAudit { }
    }
}

/**
 * One immutable audit record: the invitation's identity and parties, the [action] that occurred, its
 * [timestamp] (epoch millis from the scheduler clock), the invitation's own timing for context, and
 * the [cancelReason] / [replacedId] qualifiers when relevant. Flat and primitive-typed on purpose so
 * it maps cleanly onto a DB row or a JSON line without pulling in the generic invitation type.
 */
data class AuditEntry(
    val invitationId: UUID,
    val inviterId: UUID,
    val invitedId: UUID,
    val action: InvitationAction,
    /** When the transition occurred, epoch millis (from [Scheduler.now]). */
    val timestamp: Long,
    /** The invitation's creation time, epoch millis. */
    val createdAt: Long,
    /** The invitation's expiry, epoch millis, or null if it never expires. */
    val expiresAt: Long?,
    val cancelReason: CancelReason? = null,
    val replacedId: UUID? = null,
) {
    companion object {
        /** Build an entry from an observed [LifecycleEvent]. */
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
        )
    }
}

/**
 * Built-in [InvitationObserver] that translates observed events into [AuditEntry] records for an
 * [InvitationAudit]. Registered automatically when an audit sink is configured.
 */
class AuditObserver<T : Invitation>(private val audit: InvitationAudit) : InvitationObserver<T> {
    override fun onEvent(event: LifecycleEvent<T>) = audit.record(AuditEntry.from(event))
}
