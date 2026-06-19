package com.justxraf.invitations

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
class RateLimiter(
    private val perInviter: Limit? = null,
    private val perInvited: Limit? = null,
    private val perPair: Limit? = null,
    private val clock: () -> Long,
) {
data class Limit(val max: Int, val windowMillis: Long) {
        init {
            require(max > 0) { "max must be > 0" }
            require(windowMillis > 0) { "windowMillis must be > 0" }
        }
    }
sealed interface Decision {
        data object Allowed : Decision
data class Limited(val retryAfterMillis: Long) : Decision
    }

    private val inviterHits = ConcurrentHashMap<UUID, Window>()
    private val invitedHits = ConcurrentHashMap<UUID, Window>()
    private val pairHits = ConcurrentHashMap<Pair<UUID, UUID>, Window>()
fun isNoop(): Boolean = perInviter == null && perInvited == null && perPair == null
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
