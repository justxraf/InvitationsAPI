package com.justxraf.invitations
/**
 * Generic, server-free listener seam. The manager notifies every registered observer once per
 * successful lifecycle transition. The Bukkit event firing, logging, metrics, and audit adapters are
 * all implemented as observers over this interface.
 */
fun interface InvitationObserver<T : Invitation> {
    /** Called after a lifecycle transition has committed. Must not throw to abort the transition. */
    fun onEvent(event: LifecycleEvent<T>)
}
