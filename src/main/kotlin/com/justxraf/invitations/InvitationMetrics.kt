package com.justxraf.invitations

import java.util.concurrent.atomic.AtomicLong
/**
 * Counter sink for production monitoring. Lifecycle transitions arrive via [increment] (wired through
 * [MetricsObserver]); non-transition send outcomes (duplicate, rejected, limit, cooldown, self-invite,
 * rate-limited) via [recordSendOutcome]; and persistence errors via [recordStoreFailure]. Plug in your
 * own implementation to forward to Prometheus/Micrometer, or use [InMemory] for a snapshot.
 */
interface InvitationMetrics {
    /** Record one occurrence of a successful lifecycle [action]. */
    fun increment(action: InvitationAction)

    /** Record a non-transition send outcome (only the failure/duplicate cases are counted). */
    fun recordSendOutcome(result: InvitationManager.SendResult)

    /** Record a store write/read failure. */
    fun recordStoreFailure()

    companion object {
        /** Metrics sink that discards everything. The default. */
        @JvmField
        val Noop: InvitationMetrics = object : InvitationMetrics {
            override fun increment(action: InvitationAction) {}
            override fun recordSendOutcome(result: InvitationManager.SendResult) {}
            override fun recordStoreFailure() {}
        }
    }

    /** Thread-safe atomic-counter implementation that exposes a consistent [snapshot]. */
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
                else -> null
            }?.incrementAndGet()
        }

        override fun recordStoreFailure() { storeFailures.incrementAndGet() }
        /** Take a point-in-time copy of all counters. */
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

        /** Immutable copy of every counter at the moment [snapshot] was called. */
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
/** [InvitationObserver] that increments [InvitationMetrics] on each lifecycle transition. */
class MetricsObserver<T : Invitation>(private val metrics: InvitationMetrics) : InvitationObserver<T> {
    override fun onEvent(event: LifecycleEvent<T>) = metrics.increment(event.action)
}
