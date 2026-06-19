package com.justxraf.invitations
enum class LifecycleErrorPolicy {
ISOLATE,
PROPAGATE,
}
fun interface LifecycleErrorCallback<T : Invitation> {
    fun onLifecycleError(invitation: T, action: InvitationAction, throwable: Throwable)

    companion object {
        @JvmField
        val Noop: LifecycleErrorCallback<Invitation> =
            LifecycleErrorCallback { _, _, _ -> }
@Suppress("UNCHECKED_CAST")
        fun <T : Invitation> noop(): LifecycleErrorCallback<T> = Noop as LifecycleErrorCallback<T>
    }
}
