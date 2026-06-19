package com.justxraf.networkapi.invitations

/**
 * How [InvitationManager.send] handles a new invitation for an inviter/invited pair that already
 * has a pending invitation.
 */
enum class DuplicatePolicy {
    /** Keep the existing invitation and return [InvitationManager.SendResult.Duplicate]. */
    REJECT_EXISTING,

    /** Consume the existing invitation and register the new invitation as a fresh send. */
    REPLACE_EXISTING,

    /**
     * Keep one logical pending invite for the pair, but replace the stored invitation record with the
     * incoming one so expiry can be extended. Callers should reuse the existing invitation id when
     * they need strict id stability.
     */
    REFRESH_EXPIRY,
}
