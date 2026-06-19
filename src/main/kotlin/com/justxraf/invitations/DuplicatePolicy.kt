package com.justxraf.invitations
/**
 * How [InvitationManager.send] reacts when a pending invitation already exists for the same
 * inviter→invited pair.
 */
enum class DuplicatePolicy {
    /** Keep the existing invitation untouched and reject the new send. */
    REJECT_EXISTING,

    /** Cancel the existing invitation (reason `DUPLICATE_REPLACED`) and register the new one. */
    REPLACE_EXISTING,

    /** Keep the same logical invitation but extend its expiry to the new send's expiry. */
    REFRESH_EXPIRY,
}
