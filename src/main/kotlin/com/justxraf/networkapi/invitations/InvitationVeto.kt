package com.justxraf.networkapi.invitations

/**
 * A pre-transition veto seam — the server-free counterpart of a *cancellable* Bukkit event. Where an
 * [InvitationObserver] is notified *after* a transition commits and cannot stop it, a veto runs
 * *before* the manager mutates state and can abort the action.
 *
 * This exists so the manager can stay Bukkit-free while still supporting cancellable events: the
 * Bukkit adapter implements [InvitationVeto] by firing the matching cancellable event and reporting
 * `isCancelled`. Most hosts won't implement this directly — they'll use [InvitationHandler.validate]
 * for send-time rules — but accept/deny/cancel have no `validate` equivalent, so a veto is the only
 * pre-mutation hook for those.
 *
 * Ordering guarantee (see the roadmap): the veto fires *before* state mutation; the transition only
 * proceeds if every veto returns false; the [InvitationHandler] hook and [InvitationObserver]s fire
 * once, after mutation, only on success. A veto must not mutate the manager. Exceptions thrown here
 * are *not* isolated — a throwing veto is treated as a programming error and propagates, because
 * silently swallowing it could let a transition the host meant to block slip through. Defaults to
 * [AllowAll].
 */
fun interface InvitationVeto<T : Invitation> {
    /**
     * Decide whether to block [action] on [invitation]. Return true to cancel (the manager turns this
     * into a typed `Vetoed`/`NotFound`-style result), false to allow. [cancelReason] is non-null only
     * for [InvitationAction.CANCELLED]. Never called for [InvitationAction.SENT] — send-time vetoing
     * goes through [InvitationHandler.validate], which also carries a rejection reason.
     */
    fun isVetoed(invitation: T, action: InvitationAction, cancelReason: CancelReason?): Boolean

    companion object {
        @JvmField
        val AllowAll: InvitationVeto<Invitation> = InvitationVeto { _, _, _ -> false }

        @Suppress("UNCHECKED_CAST")
        fun <T : Invitation> allowAll(): InvitationVeto<T> = AllowAll as InvitationVeto<T>
    }
}
