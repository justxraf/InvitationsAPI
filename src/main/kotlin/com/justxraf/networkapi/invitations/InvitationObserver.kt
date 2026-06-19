package com.justxraf.networkapi.invitations

/**
 * A passive lifecycle listener for non-Bukkit environments — the server-free analogue of firing a
 * Bukkit event. Register any number of observers on the [InvitationManager] (via the builder); each
 * [onEvent] is invoked once per successful lifecycle transition, on the scheduler's main thread,
 * *after* the manager's state and the [InvitationStore] already reflect the change.
 *
 * Observers cannot veto a transition (use [InvitationHandler.validate] or, on Bukkit, a cancellable
 * event for that) and must not call back into the manager in ways that mutate it — treat the event as
 * read-only. Exceptions thrown here are isolated from the caller and from other observers per the
 * manager's [LifecycleErrorPolicy]; they never abort the transition that already happened.
 *
 * The built-in logging, metrics, and audit integrations are themselves observers ([LoggingObserver],
 * [MetricsObserver], [AuditObserver]); a Bukkit event-firing adapter is provided separately in the
 * `bukkit` package. This is a single-method interface, so Kotlin callers can pass a lambda and Java
 * callers a lambda or anonymous class.
 */
fun interface InvitationObserver<T : Invitation> {
    fun onEvent(event: LifecycleEvent<T>)
}
