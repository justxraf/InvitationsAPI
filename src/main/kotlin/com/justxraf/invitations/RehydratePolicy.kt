package com.justxraf.invitations
/**
 * Controls how [InvitationManager.rehydrate] reconciles semantically-suspect rows loaded from the
 * store (duplicate ids, duplicate inviter→invited pairs, rows over the configured caps). Malformed
 * records and bad UUIDs are handled earlier by the store's quarantine; expired rows expire inline.
 *
 * @property dropDuplicateIds drop later rows that reuse an already-seen invitation id.
 * @property dropDuplicatePairs drop later rows for a pair that already has a pending invite.
 * @property enforceCaps drop rows that would exceed `maxPerInvited`/`maxPerInviter` on load.
 * @property repairStore when a row is dropped, also delete it from the store so it stays clean.
 */
data class RehydratePolicy(
    val dropDuplicateIds: Boolean,
    val dropDuplicatePairs: Boolean,
    val enforceCaps: Boolean,
    val repairStore: Boolean,
) {
    companion object {
        /** Load the store exactly as-is, reconciling nothing. The default. */
        @JvmField
        val TRUST_STORE = RehydratePolicy(
            dropDuplicateIds = false,
            dropDuplicatePairs = false,
            enforceCaps = false,
            repairStore = false,
        )

        /** Drop every invalid row *and* delete it from the store, leaving persisted state clean. */
        @JvmField
        val REPAIR = RehydratePolicy(
            dropDuplicateIds = true,
            dropDuplicatePairs = true,
            enforceCaps = true,
            repairStore = true,
        )

        /** Drop invalid rows from the live set but leave the store untouched. */
        @JvmField
        val DROP_INVALID = RehydratePolicy(
            dropDuplicateIds = true,
            dropDuplicatePairs = true,
            enforceCaps = true,
            repairStore = false,
        )
    }
}
