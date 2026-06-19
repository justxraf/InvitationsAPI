package com.justxraf.invitations
/**
 * Threading and timing seam that keeps the core server-free. The manager schedules expiry timers and
 * dispatches post-transition callbacks through this interface; adapters provide Bukkit, Folia, or
 * plain-thread implementations, and tests provide an inline fake.
 */
interface Scheduler {
    /** Handle to a scheduled task that has not yet run, allowing it to be cancelled. */
    interface Cancellable {
        /** Cancel the task if it has not already executed. Idempotent. */
        fun cancel()
    }

    /** Current time in epoch millis. Override to drive time deterministically in tests. */
    fun now(): Long = System.currentTimeMillis()

    /** Run [block] on the platform's main/server thread (or immediately, in a single-threaded fake). */
    fun runOnMainThread(block: () -> Unit)

    /** Schedule [block] to run after [delayMillis], returning a handle to cancel it. */
    fun runLater(delayMillis: Long, block: () -> Unit): Cancellable
}
