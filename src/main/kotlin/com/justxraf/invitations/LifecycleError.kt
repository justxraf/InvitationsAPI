package com.justxraf.invitations
/** What the manager does when a handler hook or observer throws during a lifecycle dispatch. */
enum class LifecycleErrorPolicy {
    /** Swallow the exception (after routing it to the error callback) so other callbacks still run. The default. */
    ISOLATE,

    /** Rethrow the exception to the caller after notifying the error callback. */
    PROPAGATE,
}

/**
 * Notified whenever a hook or observer throws, regardless of [LifecycleErrorPolicy]. Use it to log or
 * report callback failures centrally. Configure via [InvitationManager.Builder.errorCallback].
 */
fun interface LifecycleErrorCallback<T : Invitation> {
    /** @param action the lifecycle action whose callback failed; [throwable] is what it threw. */
    fun onLifecycleError(invitation: T, action: InvitationAction, throwable: Throwable)

    companion object {
        /** Callback that ignores errors. The default. */
        @JvmField
        val Noop: LifecycleErrorCallback<Invitation> =
            LifecycleErrorCallback { _, _, _ -> }

        /** Typed accessor for [Noop]. */
        @Suppress("UNCHECKED_CAST")
        fun <T : Invitation> noop(): LifecycleErrorCallback<T> = Noop as LifecycleErrorCallback<T>
    }
}
