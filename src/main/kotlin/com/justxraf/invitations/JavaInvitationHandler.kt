package com.justxraf.invitations
abstract class JavaInvitationHandler<T : Invitation> : InvitationHandler<T> {
    override fun onSend(invitation: T) {}
    override fun onAccept(invitation: T) {}
    override fun onDeny(invitation: T) {}
    override fun onCancel(invitation: T, reason: CancelReason) {}
    override fun onExpire(invitation: T) {}
    override fun onExpiryWarning(invitation: T, remainingMillis: Long) {}
    override fun validate(invitation: T, existing: List<T>): String? = null
}
