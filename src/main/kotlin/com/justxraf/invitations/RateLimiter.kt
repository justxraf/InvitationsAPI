package com.justxraf.invitations

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
/**
 * Sliding-window rate limiter applied to [InvitationManager.send], with independent limits per
 * inviter, per invited player, and per pair. A send must pass *all* configured buckets, and a send
 * that any bucket rejects spends quota in none of them. Thread-safe; quotas are runtime-only.
 *
 * @param clock millis source, so the window can be driven deterministically in tests.
 */
class RateLimiter(
    private val perInviter: Limit? = null,
    private val perInvited: Limit? = null,
    private val perPair: Limit? = null,
    private val clock: () -> Long,
) {
    /** At most [max] events per rolling [windowMillis] window. */
    data class Limit(val max: Int, val windowMillis: Long) {
        init {
            require(max > 0) { "max must be > 0" }
            require(windowMillis > 0) { "windowMillis must be > 0" }
        }
    }

    /** Result of [tryAcquire]. */
    sealed interface Decision {
        /** The send is within all limits and quota has been consumed. */
        data object Allowed : Decision
        /** A limit was hit; retry after [retryAfterMillis]. */
        data class Limited(val retryAfterMillis: Long) : Decision
    }

    private val inviterHits = ConcurrentHashMap<UUID, Window>()
    private val invitedHits = ConcurrentHashMap<UUID, Window>()
    private val pairHits = ConcurrentHashMap<Pair<UUID, UUID>, Window>()
    /** `true` when no limit is configured, so the manager can skip the limiter entirely. */
    fun isNoop(): Boolean = perInviter == null && perInvited == null && perPair == null

    /** Atomically check every configured bucket and, if all pass, consume quota in each. */
    fun tryAcquire(inviterId: UUID, invitedId: UUID): Decision {
        val now = clock()
        // Check every bucket first; a rejected invite should not spend quota in another bucket.
        perInviter?.let { peek(inviterHits, inviterId, it, now)?.let { r -> return Decision.Limited(r) } }
        perInvited?.let { peek(invitedHits, invitedId, it, now)?.let { r -> return Decision.Limited(r) } }
        perPair?.let { peek(pairHits, inviterId to invitedId, it, now)?.let { r -> return Decision.Limited(r) } }

        perInviter?.let { record(inviterHits, inviterId, it, now) }
        perInvited?.let { record(invitedHits, invitedId, it, now) }
        perPair?.let { record(pairHits, inviterId to invitedId, it, now) }
        return Decision.Allowed
    }
    /** Drop all recorded hits for the given inviter, invited player, and pair. */
    fun forget(inviterId: UUID, invitedId: UUID) {
        inviterHits.remove(inviterId)
        invitedHits.remove(invitedId)
        pairHits.remove(inviterId to invitedId)
    }
    private fun <K> peek(map: ConcurrentHashMap<K, Window>, key: K, limit: Limit, now: Long): Long? {
        val window = map[key] ?: return null
        synchronized(window) {
            window.prune(now - limit.windowMillis)
            if (window.size() < limit.max) return null
            return window.oldest() + limit.windowMillis - now
        }
    }

    private fun <K> record(map: ConcurrentHashMap<K, Window>, key: K, limit: Limit, now: Long) {
        val window = map.computeIfAbsent(key) { Window() }
        synchronized(window) {
            window.prune(now - limit.windowMillis)
            window.add(now)
        }
    }
    private class Window {
        private val timestamps = ArrayDeque<Long>()
        fun prune(before: Long) { while (timestamps.isNotEmpty() && timestamps.first() <= before) timestamps.removeFirst() }
        fun add(now: Long) = timestamps.addLast(now)
        fun size(): Int = timestamps.size
        fun oldest(): Long = timestamps.first()
    }
}
