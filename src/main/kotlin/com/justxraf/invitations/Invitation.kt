package com.justxraf.invitations

import java.util.UUID

/**
 * The minimal contract every invitation type must satisfy.
 *
 * The API is generic over `T : Invitation` so callers can carry their own payload (party id, world,
 * display names, …) on a concrete subtype while the [InvitationManager] only relies on these fields.
 * [BasicInvitation] is the ready-made implementation for callers that need nothing extra.
 *
 * Implementations are expected to be immutable value types: the manager treats an invitation as a
 * snapshot and replaces it wholesale (for example when a duplicate policy refreshes the expiry)
 * rather than mutating it in place.
 */
interface Invitation {
    /** Stable, unique identity of this invitation. Two invitations are the same iff their ids match. */
    val id: UUID

    /** Player who sent the invitation. */
    val inviterId: UUID

    /** Player the invitation is addressed to. */
    val invitedId: UUID

    /** Creation timestamp in epoch milliseconds, used for sorting and audit. */
    val createdAt: Long

    /** Expiry timestamp in epoch milliseconds, or `null` for an invitation that never expires. */
    val expiresAt: Long?
}
