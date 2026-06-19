package com.justxraf.networkapi.invitations

import java.time.Duration
import java.util.UUID

/**
 * Small helpers for the fiddly bits of building an [Invitation]: generating an id, stamping
 * `createdAt` from a clock, and deriving `expiresAt` from a [Duration] instead of hand-computed epoch
 * millis. None of these are required — plugins with their own factories can ignore them — but they
 * keep call sites free of `System.currentTimeMillis() + ttl.toMillis()` arithmetic.
 *
 * A concrete [BasicInvitation] is provided for plugins that need an invitation with no extra domain
 * fields (e.g. a teleport request keyed only on the two players). Plugins with domain data implement
 * [Invitation] themselves and may still use [expiresAt]/[newId] below.
 */
object Invitations {

    /** A fresh random invitation id. */
    @JvmStatic
    fun newId(): UUID = UUID.randomUUID()

    /**
     * Resolve an `expiresAt` epoch-millis value [ttl] after [now]. Returns `null` for a `null` [ttl]
     * (meaning "no expiry"). Negative or zero durations resolve to an already-past timestamp, which
     * [InvitationManager.send] expires inline.
     */
    @JvmStatic
    @JvmOverloads
    fun expiresAt(ttl: Duration?, now: Long = System.currentTimeMillis()): Long? =
        ttl?.let { now + it.toMillis() }

    /**
     * Build a [BasicInvitation] between [inviterId] and [invitedId] expiring [ttl] after [now]
     * (`null` [ttl] = no expiry). Convenience for the common case; pass a [Scheduler] clock as [now]
     * to stay consistent with the manager's notion of time.
     */
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

/**
 * A no-frills [Invitation] for plugins that need only the four identity fields plus expiry. Plugins
 * that carry domain data (island id, request type, …) should implement [Invitation] on their own
 * immutable type instead. Built most easily via [Invitations.between].
 */
data class BasicInvitation(
    override val id: UUID = Invitations.newId(),
    override val inviterId: UUID,
    override val invitedId: UUID,
    override val createdAt: Long = System.currentTimeMillis(),
    override val expiresAt: Long? = null,
) : Invitation
