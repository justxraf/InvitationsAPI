package com.justxraf.networkapi.invitations

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A sliding-window rate limiter for [InvitationManager.send], independent of the per-pair cooldown
 * (which throttles repeats *after a terminal outcome*; this throttles raw send *volume*). It caps how
 * many send attempts a key may make within a rolling window, keyed three ways:
 *  - per **inviter** — "you may send at most N invites per minute",
 *  - per **invited** — "a player may be targeted at most N times per minute",
 *  - per **pair** — "you may invite *this* player at most N times per minute".
 *
 * Each configured dimension is optional. A limit is recorded only when [tryAcquire] returns allowed,
 * so a rejected send doesn't consume the inviter's budget against the *other* dimensions — all three
 * are checked first, then all three recorded only on success (no partial consumption).
 *
 * Time comes from a [clock] (the manager passes [Scheduler.now]) so it's fully testable. State is
 * runtime-only and lives in memory: it is **not persisted** and resets on restart — see
 * [InvitationManager] cooldown documentation. Thread-safe; intended to be consulted under no lock.
 */
class RateLimiter(
    private val perInviter: Limit? = null,
    private val perInvited: Limit? = null,
    private val perPair: Limit? = null,
    private val clock: () -> Long,
) {
    /** A "[max] events per [windowMillis]" rule. */
    data class Limit(val max: Int, val windowMillis: Long) {
        init {
            require(max > 0) { "max must be > 0" }
            require(windowMillis > 0) { "windowMillis must be > 0" }
        }
    }

    /** Outcome of a [tryAcquire] check. */
    sealed interface Decision {
        data object Allowed : Decision
        /** Blocked; [retryAfterMillis] is how long until the oldest counted event falls out of the window. */
        data class Limited(val retryAfterMillis: Long) : Decision
    }

    private val inviterHits = ConcurrentHashMap<UUID, Window>()
    private val invitedHits = ConcurrentHashMap<UUID, Window>()
    private val pairHits = ConcurrentHashMap<Pair<UUID, UUID>, Window>()

    /** True if no dimension is configured — the manager skips the limiter entirely. */
    fun isNoop(): Boolean = perInviter == null && perInvited == null && perPair == null

    /**
     * Check (and on success, record) a send from [inviterId] to [invitedId] against every configured
     * dimension. Returns [Decision.Allowed] and consumes one token from each dimension, or
     * [Decision.Limited] (consuming nothing) carrying the soonest retry across the breached dimensions.
     */
    fun tryAcquire(inviterId: UUID, invitedId: UUID): Decision {
        val now = clock()
        // Check all dimensions first; only commit if every one has headroom.
        perInviter?.let { peek(inviterHits, inviterId, it, now)?.let { r -> return Decision.Limited(r) } }
        perInvited?.let { peek(invitedHits, invitedId, it, now)?.let { r -> return Decision.Limited(r) } }
        perPair?.let { peek(pairHits, inviterId to invitedId, it, now)?.let { r -> return Decision.Limited(r) } }

        perInviter?.let { record(inviterHits, inviterId, it, now) }
        perInvited?.let { record(invitedHits, invitedId, it, now) }
        perPair?.let { record(pairHits, inviterId to invitedId, it, now) }
        return Decision.Allowed
    }

    /** Forget all recorded events for these keys — used when an invite is consumed, to free memory. */
    fun forget(inviterId: UUID, invitedId: UUID) {
        inviterHits.remove(inviterId)
        invitedHits.remove(invitedId)
        pairHits.remove(inviterId to invitedId)
    }

    /** Returns retry-after millis if [key] is at its limit, else null (has headroom). Prunes stale events. */
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

    /** A small ring of event timestamps for one key, guarded by its own monitor. */
    private class Window {
        private val timestamps = ArrayDeque<Long>()
        fun prune(before: Long) { while (timestamps.isNotEmpty() && timestamps.first() <= before) timestamps.removeFirst() }
        fun add(now: Long) = timestamps.addLast(now)
        fun size(): Int = timestamps.size
        fun oldest(): Long = timestamps.first()
    }
}
