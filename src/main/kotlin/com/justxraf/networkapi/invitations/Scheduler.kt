package com.justxraf.networkapi.invitations

/**
 * Scheduling seam that keeps the core API free of any Bukkit dependency (and so unit-testable).
 * On a server it's backed by [bukkit.BukkitScheduler]; in tests by a fake clock.
 */
interface Scheduler {

    /** A scheduled task that can be cancelled. */
    interface Cancellable {
        fun cancel()
    }

    /** Scheduler's own clock, epoch millis. Expiry delays are derived from this, not the wall clock. */
    fun now(): Long = System.currentTimeMillis()

    /** Run [block] on the main server thread (handlers mutate game state there). */
    fun runOnMainThread(block: () -> Unit)

    /** Run [block] once after [delayMillis]; the handle lets the manager cancel a pending expiry. */
    fun runLater(delayMillis: Long, block: () -> Unit): Cancellable
}
