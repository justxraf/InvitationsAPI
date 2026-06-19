package com.justxraf.invitations
fun interface InvitationVeto<T : Invitation> {
fun isVetoed(invitation: T, action: InvitationAction, cancelReason: CancelReason?): Boolean

    companion object {
        @JvmField
        val AllowAll: InvitationVeto<Invitation> = InvitationVeto { _, _, _ -> false }

        @Suppress("UNCHECKED_CAST")
        fun <T : Invitation> allowAll(): InvitationVeto<T> = AllowAll as InvitationVeto<T>
    }
}
