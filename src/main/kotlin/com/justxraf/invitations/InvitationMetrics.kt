package com.justxraf.invitations

import java.util.concurrent.atomic.AtomicLong
interface InvitationMetrics {
fun increment(action: InvitationAction)
fun recordSendOutcome(result: InvitationManager.SendResult)
fun recordStoreFailure()

    companion object {
@JvmField
        val Noop: InvitationMetrics = object : InvitationMetrics {
            override fun increment(action: InvitationAction) {}
            override fun recordSendOutcome(result: InvitationManager.SendResult) {}
            override fun recordStoreFailure() {}
        }
    }
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
class MetricsObserver<T : Invitation>(private val metrics: InvitationMetrics) : InvitationObserver<T> {
    override fun onEvent(event: LifecycleEvent<T>) = metrics.increment(event.action)
}
