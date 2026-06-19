package com.justxraf.invitations
/**
 * Java-friendly base class for [InvitationHandler]. Kotlin interface defaults are not visible as
 * overridable no-ops to all Java compilers, so this abstract class re-declares every hook with an
 * empty body, letting Java subclasses `@Override` only the hooks they care about.
 */
abstract class JavaInvitationHandler<T : Invitation> : InvitationHandler<T> {
    override fun onSend(invitation: T) {}
    override fun onAccept(invitation: T) {}
    override fun onDeny(invitation: T) {}
    override fun onCancel(invitation: T, reason: CancelReason) {}
    override fun onExpire(invitation: T) {}
    override fun onExpiryWarning(invitation: T, remainingMillis: Long) {}
    override fun validate(invitation: T, existing: List<T>): String? = null
}
