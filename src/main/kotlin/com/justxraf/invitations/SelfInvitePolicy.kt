package com.justxraf.invitations
/** How [InvitationManager.send] treats an invitation whose `inviterId == invitedId`. */
enum class SelfInvitePolicy {
    /** Reject the send with `SendResult.SelfInvite`. The default. */
    REJECT,

    /** Allow self-invites through, for systems where inviting yourself is meaningful. */
    ALLOW,
}
