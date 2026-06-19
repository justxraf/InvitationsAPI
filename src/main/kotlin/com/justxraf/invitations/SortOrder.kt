package com.justxraf.invitations
/** Sort direction for the query variants of [InvitationManager], keyed on [Invitation.createdAt]. */
enum class SortOrder {
    /** Most recently created invitations first. */
    NEWEST_FIRST,

    /** Oldest invitations first. */
    OLDEST_FIRST,
}
