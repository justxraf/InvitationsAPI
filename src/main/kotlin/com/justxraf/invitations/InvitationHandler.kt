package com.justxraf.invitations
interface InvitationHandler<T : Invitation> {
fun onSend(invitation: T) {}
fun onAccept(invitation: T) {}
fun onDeny(invitation: T) {}
fun onCancel(invitation: T, reason: CancelReason) {}
fun onExpire(invitation: T) {}
fun onExpiryWarning(invitation: T, remainingMillis: Long) {}
fun validate(invitation: T, existing: List<T>): String? = null
}
enum class CancelReason {
REVOKED,
PARTY_CLEARED,
DUPLICATE_REPLACED,
PLAYER_QUIT,
SERVER_SHUTDOWN,
TARGET_LIMIT_REACHED,
ADMIN_CLEARED,
}
