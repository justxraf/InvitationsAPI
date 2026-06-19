package com.justxraf.networkapi.invitations

import java.util.concurrent.atomic.AtomicLong

/**
 * A counter seam for production observability. The manager increments one counter per outcome so a
 * host can export them to Prometheus, bStats, a `/stats` command, etc. without the core depending on
 * any metrics library. Pass an implementation to the builder; the built-in [MetricsObserver] feeds
 * the lifecycle counters from observed events, while [recordSendOutcome] and [recordStoreFailure] are
 * called by the manager directly (they cover outcomes that never produce a lifecycle event, such as a
 * rejected or duplicate send, or a persistence failure).
 *
 * Implementations must be thread-safe: counters may be touched from the main thread and from
 * scheduler/expiry callbacks. The provided [InMemory] uses atomics and is a fine default for a
 * `/stats` command. Defaults to [Noop].
 */
interface InvitationMetrics {
    /** A lifecycle transition was observed. */
    fun increment(action: InvitationAction)

    /**
     * The outcome of a [InvitationManager.send] call, including the non-event outcomes
     * (duplicate, rejected, limit reached, cooldown, self-invite) that [increment] never sees.
     * [InvitationManager.SendResult.Accepted]/`Replaced`/`Refreshed` also surface here so a host can
     * count send *attempts* vs. successes.
     */
    fun recordSendOutcome(result: InvitationManager.SendResult)

    /** A [InvitationStore] `save`/`remove`/`load` call threw. */
    fun recordStoreFailure()

    companion object {
        /** Discards every metric. The manager's default. */
        @JvmField
        val Noop: InvitationMetrics = object : InvitationMetrics {
            override fun increment(action: InvitationAction) {}
            override fun recordSendOutcome(result: InvitationManager.SendResult) {}
            override fun recordStoreFailure() {}
        }
    }

    /**
     * A thread-safe, in-process counter set — good enough for a `/stats` command. Read a snapshot via
     * [snapshot]. The lifecycle counters (`sent`, `accepted`, …) come from observed events; `send*`
     * counters classify send outcomes; `storeFailures` counts persistence errors.
     */
    class InMemory : InvitationMetrics {
        private val counters = InvitationAction.entries.associateWith { AtomicLong() }
        private val duplicate = AtomicLong()
        private val rejected = AtomicLong()
        private val limitReached = AtomicLong()
        private val cooldownActive = AtomicLong()
        private val selfInvite = AtomicLong()
        private val rateLimited = AtomicLong()
        private val storeFailures = AtomicLong()

        override fun increment(action: InvitationAction) { counters.getValue(action).incrementAndGet() }

        override fun recordSendOutcome(result: InvitationManager.SendResult) {
            when (result) {
                is InvitationManager.SendResult.Duplicate -> duplicate
                is InvitationManager.SendResult.Rejected -> rejected
                is InvitationManager.SendResult.PolicyRejected -> rejected
                is InvitationManager.SendResult.RateLimited -> rateLimited
                is InvitationManager.SendResult.LimitReached -> limitReached
                is InvitationManager.SendResult.CooldownActive -> cooldownActive
                InvitationManager.SendResult.SelfInvite -> selfInvite
                // Accepted / Replaced / Refreshed produce a lifecycle event counted via increment().
                else -> null
            }?.incrementAndGet()
        }

        override fun recordStoreFailure() { storeFailures.incrementAndGet() }

        /** An immutable point-in-time copy of every counter. */
        fun snapshot(): Snapshot = Snapshot(
            sent = counters.getValue(InvitationAction.SENT).get(),
            accepted = counters.getValue(InvitationAction.ACCEPTED).get(),
            denied = counters.getValue(InvitationAction.DENIED).get(),
            cancelled = counters.getValue(InvitationAction.CANCELLED).get(),
            expired = counters.getValue(InvitationAction.EXPIRED).get(),
            replaced = counters.getValue(InvitationAction.REPLACED).get(),
            duplicate = duplicate.get(),
            rejected = rejected.get(),
            limitReached = limitReached.get(),
            cooldownActive = cooldownActive.get(),
            selfInvite = selfInvite.get(),
            rateLimited = rateLimited.get(),
            storeFailures = storeFailures.get(),
        )

        data class Snapshot(
            val sent: Long,
            val accepted: Long,
            val denied: Long,
            val cancelled: Long,
            val expired: Long,
            val replaced: Long,
            val duplicate: Long,
            val rejected: Long,
            val limitReached: Long,
            val cooldownActive: Long,
            val selfInvite: Long,
            val rateLimited: Long,
            val storeFailures: Long,
        )
    }
}

/**
 * Built-in [InvitationObserver] that feeds the lifecycle counters of an [InvitationMetrics] from
 * observed events. Registered automatically when metrics are configured.
 */
class MetricsObserver<T : Invitation>(private val metrics: InvitationMetrics) : InvitationObserver<T> {
    override fun onEvent(event: LifecycleEvent<T>) = metrics.increment(event.action)
}
