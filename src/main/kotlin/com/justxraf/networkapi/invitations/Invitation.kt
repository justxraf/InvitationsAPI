package com.justxraf.networkapi.invitations

import java.util.UUID

/**
 * The minimal contract every invitation shares. Plugins implement it with their own concrete type
 * (island invite, teleport request, …) and keep their domain fields there; the [InvitationManager]
 * only ever reads what's below. Implementations should be immutable value objects.
 */
interface Invitation {
    /** Stable identity. Two invitations are "the same" iff their ids match. */
    val id: UUID

    val inviterId: UUID
    val invitedId: UUID

    /** Creation time, epoch millis. */
    val createdAt: Long

    /** Auto-expire time, epoch millis, or `null` for no timeout. */
    val expiresAt: Long?
}
