package com.justxraf.invitations
/**
 * Pre-mutation veto seam for vetoable actions (send/accept/deny/cancel). Runs before any state
 * changes; returning `true` aborts the action and surfaces a `Vetoed` result. The Bukkit
 * `EventFiringVeto` implements this by firing a cancellable pre-event and reporting `isCancelled`.
 */
fun interface InvitationVeto<T : Invitation> {
    /** @return `true` to veto (abort) the [action], `false` to allow it. */
    fun isVetoed(invitation: T, action: InvitationAction, cancelReason: CancelReason?): Boolean

    companion object {
        /** A veto that allows everything — the default when no veto is configured. */
        @JvmField
        val AllowAll: InvitationVeto<Invitation> = InvitationVeto { _, _, _ -> false }

        /** Typed accessor for [AllowAll]. */
        @Suppress("UNCHECKED_CAST")
        fun <T : Invitation> allowAll(): InvitationVeto<T> = AllowAll as InvitationVeto<T>
    }
}
