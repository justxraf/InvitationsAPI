package com.justxraf.invitations

import java.time.Duration
import java.util.UUID

/**
 * Factory helpers for building [BasicInvitation]s and computing expiry timestamps without juggling
 * epoch millis by hand. Every method is `@JvmStatic` so Java callers use `Invitations.between(...)`.
 */
object Invitations {
    /** Generate a fresh random invitation id. */
    @JvmStatic
    fun newId(): UUID = UUID.randomUUID()

    /**
     * Resolve a [ttl] into an absolute expiry timestamp in epoch millis, relative to [now].
     *
     * @return `now + ttl` in millis, or `null` when [ttl] is `null` (a non-expiring invitation).
     */
    @JvmStatic
    @JvmOverloads
    fun expiresAt(ttl: Duration?, now: Long = System.currentTimeMillis()): Long? =
        ttl?.let { now + it.toMillis() }

    /** Alias for [expiresAt] that reads more naturally at call sites: `expiresAfter(Duration.ofMinutes(2))`. */
    @JvmStatic
    @JvmOverloads
    fun expiresAfter(ttl: Duration?, now: Long = System.currentTimeMillis()): Long? = expiresAt(ttl, now)
    /**
     * Build a [BasicInvitation] between two players with a generated id and computed expiry.
     *
     * @param ttl how long the invitation stays pending; `null` means it never expires.
     * @param now the creation instant in epoch millis; defaults to the wall clock.
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
 * Default [Invitation] implementation for callers that need no extra payload. Prefer the
 * [Invitations] factory methods over the constructor when you want generated ids and computed expiry.
 */
data class BasicInvitation(
    override val id: UUID = Invitations.newId(),
    override val inviterId: UUID,
    override val invitedId: UUID,
    override val createdAt: Long = System.currentTimeMillis(),
    override val expiresAt: Long? = null,
) : Invitation
