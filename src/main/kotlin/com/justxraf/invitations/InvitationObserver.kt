package com.justxraf.invitations
fun interface InvitationObserver<T : Invitation> {
    fun onEvent(event: LifecycleEvent<T>)
}
