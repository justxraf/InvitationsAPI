package com.justxraf.networkapi.invitations

/**
 * Governs how [InvitationManager.rehydrate] treats the rows returned by [InvitationStore.load] when
 * they aren't a clean, consistent set — duplicate ids, two pending invites for the same
 * (inviter, invited) pair, or a row that would push a player over a configured cap.
 *
 * Malformed rows (bad UUIDs, unparsable fields) are handled *inside* the [InvitationStore] /
 * [InvitationSerializer] before they ever reach the manager; this policy is purely about the
 * *semantic* consistency of the well-formed invitations that loaded successfully. Already-expired
 * rows are always handled the same way regardless of policy: re-armed inline and expired immediately
 * (see [InvitationManager.rehydrate]).
 *
 * @param dropDuplicateIds when the store yields the same [Invitation.id] twice, keep the first and
 *   drop the rest (and delete them from the store). When false, a duplicate id throws.
 * @param dropDuplicatePairs when two distinct invitations share the same (inviter, invited) pair,
 *   keep the newest by [Invitation.createdAt] and drop the older (deleting it from the store). When
 *   false, both are kept (matching the historical "trust the store exactly" behaviour).
 * @param enforceCaps when true, apply the manager's `maxPerInviter` / `maxPerInvited` caps on load,
 *   dropping the oldest invitations that exceed a cap (and deleting them from the store). When false,
 *   caps are ignored on load — the store may legitimately hold more than the live cap if the cap was
 *   lowered between runs.
 * @param repairStore when true, every drop above is also removed from the [InvitationStore] so the
 *   on-disk state is healed. When false, drops are in-memory only and the store is left untouched.
 */
data class RehydratePolicy(
    val dropDuplicateIds: Boolean,
    val dropDuplicatePairs: Boolean,
    val enforceCaps: Boolean,
    val repairStore: Boolean,
) {
    companion object {
        /** Trust the store exactly: keep every well-formed row, no de-duplication, no cap enforcement. */
        @JvmField
        val TRUST_STORE = RehydratePolicy(
            dropDuplicateIds = false,
            dropDuplicatePairs = false,
            enforceCaps = false,
            repairStore = false,
        )

        /**
         * The production default: heal duplicate ids and duplicate pairs, enforce caps, and write the
         * repairs back to the store so the inconsistency doesn't recur on the next restart.
         */
        @JvmField
        val REPAIR = RehydratePolicy(
            dropDuplicateIds = true,
            dropDuplicatePairs = true,
            enforceCaps = true,
            repairStore = true,
        )

        /** Drop inconsistent rows from memory but leave the store untouched (read-only repair). */
        @JvmField
        val DROP_INVALID = RehydratePolicy(
            dropDuplicateIds = true,
            dropDuplicatePairs = true,
            enforceCaps = true,
            repairStore = false,
        )
    }
}
