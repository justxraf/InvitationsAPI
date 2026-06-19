package com.justxraf.networkapi.invitations

/**
 * How [InvitationManager.send] handles an invitation whose [Invitation.inviterId] equals its
 * [Invitation.invitedId] (a player inviting themselves).
 */
enum class SelfInvitePolicy {
    /**
     * Reject the send outright with [InvitationManager.SendResult.SelfInvite] before [validate] or any
     * indexing runs. The safe default for almost every plugin.
     */
    REJECT,

    /**
     * Treat a self-invite like any other invitation. Use only if a plugin has a legitimate "invite
     * yourself" flow; [InvitationHandler.validate] can still reject it.
     */
    ALLOW,
}
