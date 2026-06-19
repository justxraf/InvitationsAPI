package com.justxraf.invitations

import java.time.Duration

/**
 * The single source of "now" for every time decision in the manager: expiry, cooldowns, warning
 * offsets, and lifecycle event timestamps. Keeping it behind one seam makes those decisions testable
 * (tests inject a controllable clock) and consistent (nothing reads the wall clock directly).
 *
 * By default the manager derives its clock from the [Scheduler] it already owns, so `Scheduler.now()`
 * and `Clock.now()` never disagree. Provide a custom [Clock] only when time must advance independently
 * of the scheduler (for example a fixed clock in a test that drives timers manually).
 */
fun interface Clock {
    /** Current time in epoch milliseconds. */
    fun now(): Long

    /** Time [duration] from now, in epoch milliseconds. */
    fun plus(duration: Duration): Long = now() + duration.toMillis()

    companion object {
        /** A clock backed by the system wall clock. */
        @JvmField
        val SYSTEM: Clock = Clock { System.currentTimeMillis() }

        /** A clock that always reports [fixedMillis]; useful in tests. */
        @JvmStatic
        fun fixed(fixedMillis: Long): Clock = Clock { fixedMillis }

        /** A clock that reads "now" from a [Scheduler], so engine time never drifts from timer time. */
        @JvmStatic
        fun fromScheduler(scheduler: Scheduler): Clock = Clock { scheduler.now() }
    }
}
