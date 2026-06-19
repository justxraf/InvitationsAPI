package com.justxraf.networkapi.invitations

/**
 * Java-friendly base for [InvitationHandler]. Kotlin's interface default methods don't surface as
 * usable defaults to Java callers — a Java class implementing [InvitationHandler] directly is forced
 * to override every hook. Extend this abstract class instead and override only the hooks you need;
 * the rest are no-ops (and [validate] allows by default).
 *
 * ```java
 * class MyHandler extends JavaInvitationHandler<MyInvite> {
 *     @Override public void onAccept(MyInvite invite) { ... }
 * }
 * ```
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
