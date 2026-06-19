package com.justxraf.invitations

import java.util.UUID
interface Invitation {
val id: UUID

    val inviterId: UUID
    val invitedId: UUID
val createdAt: Long
val expiresAt: Long?
}
