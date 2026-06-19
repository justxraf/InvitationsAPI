package com.justxraf.networkapi.invitations

/**
 * What the manager does when a *callback* throws — an [InvitationHandler] hook ([InvitationHandler.onSend],
 * `onAccept`, …) or an [InvitationObserver] (which includes the built-in logging/metrics/audit
 * observers and the Bukkit event-firing adapter). This governs callbacks only; [InvitationStore]
 * failures are a separate concern (see the §3 store-failure policy roadmap item) and [InvitationHandler.validate]
 * already reports rejection through its return value rather than by throwing.
 *
 * The transition the callback was reacting to has *already* happened by the time a hook runs — state
 * and store are committed — so propagating an exception cannot roll it back, only surface it. The
 * default ([ISOLATE]) keeps one misbehaving handler or observer from breaking the others or the
 * scheduler tick.
 */
enum class LifecycleErrorPolicy {
    /**
     * Swallow the exception so it cannot abort the dispatch tick or the remaining callbacks: log it
     * at ERROR and route it to [onLifecycleError], then continue. Recommended default — one bad
     * observer shouldn't take down expiry timers or sibling listeners.
     */
    ISOLATE,

    /**
     * Still log and route to [onLifecycleError], but then rethrow so the exception propagates out of
     * the scheduler task. Use only if your scheduler/host has a meaningful top-level error boundary
     * and you'd rather fail loud than continue.
     */
    PROPAGATE,
}

/**
 * Notified when a handler hook or observer throws, regardless of [LifecycleErrorPolicy]. Lets a host
 * react (alert, increment an error metric, disable a flaky integration) with full context: the
 * [invitation] involved, the [action] whose callback failed, and the [throwable]. Must not throw; if
 * it does, that secondary exception is swallowed. Defaults to [Noop].
 */
fun interface LifecycleErrorCallback<T : Invitation> {
    fun onLifecycleError(invitation: T, action: InvitationAction, throwable: Throwable)

    companion object {
        @JvmField
        val Noop: LifecycleErrorCallback<Invitation> =
            LifecycleErrorCallback { _, _, _ -> }

        /** The [Noop] callback typed for any [T]; it ignores its arguments so the cast is safe. */
        @Suppress("UNCHECKED_CAST")
        fun <T : Invitation> noop(): LifecycleErrorCallback<T> = Noop as LifecycleErrorCallback<T>
    }
}
