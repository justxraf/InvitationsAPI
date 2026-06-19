package com.justxraf.invitations

import java.time.Duration
import java.util.UUID
object Invitations {
@JvmStatic
    fun newId(): UUID = UUID.randomUUID()
@JvmStatic
    @JvmOverloads
    fun expiresAt(ttl: Duration?, now: Long = System.currentTimeMillis()): Long? =
        ttl?.let { now + it.toMillis() }
@JvmStatic
    @JvmOverloads
    fun between(
        inviterId: UUID,
        invitedId: UUID,
        ttl: Duration? = null,
        now: Long = System.currentTimeMillis(),
    ): BasicInvitation = BasicInvitation(
        id = newId(),
        inviterId = inviterId,
        invitedId = invitedId,
        createdAt = now,
        expiresAt = expiresAt(ttl, now),
    )
}
data class BasicInvitation(
    override val id: UUID = Invitations.newId(),
    override val inviterId: UUID,
    override val invitedId: UUID,
    override val createdAt: Long = System.currentTimeMillis(),
    override val expiresAt: Long? = null,
) : Invitation
