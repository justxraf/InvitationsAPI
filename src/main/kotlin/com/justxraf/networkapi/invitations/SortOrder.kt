package com.justxraf.networkapi.invitations

/** Stable ordering for the sorted query variants on [InvitationManager], keyed on [Invitation.createdAt]. */
enum class SortOrder {
    /** Most recently created first — the usual "latest invite on top" list order. */
    NEWEST_FIRST,

    /** Oldest created first. */
    OLDEST_FIRST,
}
