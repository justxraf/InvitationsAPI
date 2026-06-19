package com.justxraf.invitations

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Generic, server-free invitation engine.
 *
 * ## Time and the clock
 * Every time decision — expiry, cooldowns, warning offsets, lifecycle event timestamps — reads from a
 * single [Clock]. By default the clock is derived from the [scheduler], so engine time and timer time
 * never drift; tests can inject a fixed clock via the builder. Per-invite timer *delays* are still
 * computed against `scheduler.now()` because that is the clock the scheduler itself fires on.
 *
 * ## Expiry semantics and guardrails
 * - **No-expiry invitations are allowed by default** (an invite with `expiresAt == null` never lapses).
 *   Enable [Builder.requireExpiry] to reject them with [SendResult.ExpiryRequired].
 * - [Builder.maxExpiry] caps how far in the future `expiresAt` may be; longer sends are rejected with
 *   [SendResult.ExpiryTooLong].
 * - Expiry warning timers are armed alongside the expiry timer and are cancelled together with it on
 *   any terminal action (see `cancelTimers`).
 *
 * ## Expiry during downtime
 * A pending invite's expiry is enforced by an in-process timer, so an invite that lapses **while the
 * server is down** has no timer to fire. Such invites are expired lazily: [rehydrate] re-arms timers
 * and expires anything already past, and [sweepExpired] is the explicit bulk path — call it after
 * `rehydrate()` at startup (and optionally on an interval) to expire lapsed rows, using the store's
 * targeted [InvitationStore.loadExpired] query where available. Invitations therefore expire while the
 * server is running or on the next startup, never silently mid-downtime.
 *
 * ## Lifecycle states
 * Terminal invitations (accepted, denied, cancelled, expired) are **removed**, not retained as
 * historical rows; the only persisted state is `PENDING`. Callers that need an audit trail of terminal
 * outcomes should wire an [InvitationAudit] sink rather than expecting the store to keep dead rows.
 *
 * Callbacks never run while the manager lock is held; see the per-member notes for the full
 * concurrency contract.
 */
class InvitationManager<T : Invitation>(
    private val handler: InvitationHandler<T>,
    private val scheduler: Scheduler,
    private val maxPerInviter: Int? = null,
    private val store: InvitationStore<T> = InvitationStore.InMemory(),
    private val maxPerInvited: Int? = null,
    private val pairCooldownMillis: Long? = null,
    private val duplicatePolicy: DuplicatePolicy = DuplicatePolicy.REJECT_EXISTING,
    private val selfInvitePolicy: SelfInvitePolicy = SelfInvitePolicy.REJECT,
    private val validationPolicies: List<ValidationPolicy<T>> = emptyList(),
    private val rateLimiter: RateLimiter? = null,
    private val expiryWarningOffsetsMillis: List<Long> = emptyList(),
    observers: List<InvitationObserver<T>> = emptyList(),
    private val vetoes: List<InvitationVeto<T>> = emptyList(),
    private val logger: InvitationLogger = InvitationLogger.Noop,
    private val metrics: InvitationMetrics = InvitationMetrics.Noop,
    audit: InvitationAudit = InvitationAudit.Noop,
    private val errorPolicy: LifecycleErrorPolicy = LifecycleErrorPolicy.ISOLATE,
    private val errorCallback: LifecycleErrorCallback<T> = LifecycleErrorCallback.noop(),
    private val storeFailurePolicy: StoreFailurePolicy = StoreFailurePolicy.FAIL_BEFORE_MUTATING,
    private val storeWriteRetries: Int = 2,
    private val rehydratePolicy: RehydratePolicy = RehydratePolicy.REPAIR,
    // Expiry guardrails. By default the manager preserves the prototype's permissive behavior:
    // invitations may have no expiry, and any expiry is accepted. Production callers opt into the
    // stricter rules via the builder.
    private val requireExpiry: Boolean = false,
    private val maxExpiryMillis: Long? = null,
    // The single source of "now" for engine decisions. Defaults to the scheduler so timer time and
    // engine time never drift; see the class-level concurrency/time contract.
    private val clock: Clock = Clock.fromScheduler(scheduler),
) {
    // Keep the secondary indexes in step with byId; callers use all three lookup paths.
    private val byId = ConcurrentHashMap<UUID, T>()
    private val byInvited = ConcurrentHashMap<UUID, MutableSet<UUID>>()
    private val byInviter = ConcurrentHashMap<UUID, MutableSet<UUID>>()
    private val timers = ConcurrentHashMap<UUID, Scheduler.Cancellable>()
    private val warningTimers = ConcurrentHashMap<UUID, MutableList<Scheduler.Cancellable>>()
    // Pair cooldowns are deliberately runtime-only: they live in this in-memory map and are NOT
    // persisted to the store, so they reset on every restart. This is anti-spam friction, not a
    // durable ban — a server bounce clears all pending cooldowns. See `recordCooldown`/the
    // `pairCooldownMillis` builder option. To survive restarts, gate sends with a durable
    // `ValidationPolicy` backed by your own store instead of relying on this map.
    private val pairCooldownUntil = ConcurrentHashMap<PairKey, Long>()
    private val lock = Any()

    @Volatile private var healthy = true

    /**
     * Whether the manager is still healthy. Only ever flips to `false` under
     * [StoreFailurePolicy.MARK_UNHEALTHY] when a store write fails; otherwise always `true`.
     */
    fun isHealthy(): Boolean = healthy
// Logging, metrics and audit hooks ride the same observer path as user code.
    private val observers: List<InvitationObserver<T>> = buildList {
        addAll(observers)
        if (logger !== InvitationLogger.Noop) add(LoggingObserver(logger))
        if (metrics !== InvitationMetrics.Noop) add(MetricsObserver(metrics))
        if (audit !== InvitationAudit.Noop) add(AuditObserver(audit))
    }
    /** Every possible outcome of [send]. Exactly one is returned per call. */
    sealed interface SendResult {
        /** The invitation was registered. */
        data class Accepted(val invitationId: UUID) : SendResult
        /** A pending invite for the pair already exists ([DuplicatePolicy.REJECT_EXISTING]). */
        data class Duplicate(val existingId: UUID) : SendResult
        /** The existing invite was replaced by this one ([DuplicatePolicy.REPLACE_EXISTING]). */
        data class Replaced(val replacedId: UUID, val invitationId: UUID) : SendResult
        /** The existing invite's expiry was refreshed ([DuplicatePolicy.REFRESH_EXPIRY]). */
        data class Refreshed(val invitationId: UUID) : SendResult
        /** Rejected by an [InvitationHandler.validate] hook, carrying its message. */
        data class Rejected(val reason: String) : SendResult
        /** Rejected by a [ValidationPolicy], carrying the structured reason. */
        data class PolicyRejected(val reason: RejectionReason) : SendResult
        /** Rejected by the [RateLimiter]; retry after [retryAfterMillis]. */
        data class RateLimited(val retryAfterMillis: Long) : SendResult
        /** Rejected because `maxPerInviter`/`maxPerInvited` was reached. */
        data class LimitReached(val limit: Int) : SendResult
        /** Rejected because the pair is in a post-action cooldown; retry after [remainingMillis]. */
        data class CooldownActive(val remainingMillis: Long) : SendResult
        /** Rejected because inviter == invited under [SelfInvitePolicy.REJECT]. */
        data object SelfInvite : SendResult
        /** Rejected because [requireExpiry] is on and the invitation had no `expiresAt`. */
        data object ExpiryRequired : SendResult
        /** Rejected because the invitation's lifetime exceeded the configured [maxExpiryMillis] guardrail. */
        data class ExpiryTooLong(val maxMillis: Long, val requestedMillis: Long) : SendResult
        /** Cancelled by a pre-send veto/event before any state changed. */
        data object Vetoed : SendResult
        /** The store write failed under [StoreFailurePolicy.FAIL_BEFORE_MUTATING]; no state changed. */
        data class StoreFailure(val cause: Throwable) : SendResult
    }

    /** Thrown internally when a store write fails under [StoreFailurePolicy.FAIL_BEFORE_MUTATING]. */
    class StoreWriteFailedException(message: String, cause: Throwable) : RuntimeException(message, cause)

    /**
     * Register a new invitation, applying self-invite/expiry guardrails, cooldowns, duplicate policy,
     * validation, vetoes, rate limits, and caps in that order. See [SendResult] for the outcomes.
     *
     * @param actor optional authorization context; an admin actor bypasses rate limiting.
     */
    @JvmOverloads
    fun send(invitation: T, actor: ActorContext? = null): SendResult {
        val result = sendInternal(invitation, actor)
        metrics.recordSendOutcome(result)
        return result
    }

    private fun sendInternal(invitation: T, actor: ActorContext?): SendResult {
        if (selfInvitePolicy == SelfInvitePolicy.REJECT && invitation.inviterId == invitation.invitedId) {
            return SendResult.SelfInvite
        }
        val expiresAt = invitation.expiresAt
        if (requireExpiry && expiresAt == null) return SendResult.ExpiryRequired
        if (expiresAt != null) maxExpiryMillis?.let { max ->
            val requested = expiresAt - clock.now()
            if (requested > max) return SendResult.ExpiryTooLong(max, requested)
        }
        synchronized(lock) {
            activeCooldownRemaining(invitation.inviterId, invitation.invitedId)?.let {
                return SendResult.CooldownActive(it)
            }
            if (duplicatePolicy == DuplicatePolicy.REJECT_EXISTING) findPair(invitation.inviterId, invitation.invitedId)?.let {
                recordCooldown(invitation.inviterId, invitation.invitedId)
                return SendResult.Duplicate(it.id)
            }
        }
        for (policy in validationPolicies) {
            policy.validate(invitation, getInvitesFrom(invitation.inviterId), actor)?.let {
                return SendResult.PolicyRejected(it)
            }
        }
        handler.validate(invitation, getInvitesFrom(invitation.inviterId))?.let {
            return SendResult.Rejected(it)
        }
        if (isVetoed(invitation, InvitationAction.SENT)) return SendResult.Vetoed
        if (actor?.admin != true) rateLimiter?.let { limiter ->
            when (val d = limiter.tryAcquire(invitation.inviterId, invitation.invitedId)) {
                is RateLimiter.Decision.Limited -> return SendResult.RateLimited(d.retryAfterMillis)
                RateLimiter.Decision.Allowed -> Unit
            }
        }

        // Duplicate policies update the indexes under lock, then run store writes and callbacks after.
        var duplicateMutation: DuplicateMutation<T>? = null
        synchronized(lock) {
            activeCooldownRemaining(invitation.inviterId, invitation.invitedId)?.let {
                return SendResult.CooldownActive(it)
            }
            findPair(invitation.inviterId, invitation.invitedId)?.let {
                when (duplicatePolicy) {
                    DuplicatePolicy.REJECT_EXISTING -> {
                        recordCooldown(invitation.inviterId, invitation.invitedId)
                        return SendResult.Duplicate(it.id)
                    }
                    DuplicatePolicy.REPLACE_EXISTING -> {
                        unindex(it.id)
                        index(invitation)
                        duplicateMutation = DuplicateMutation(
                            existing = it,
                            result = SendResult.Replaced(it.id, invitation.id),
                            fireCancel = true,
                            fireSend = true,
                        )
                    }
                    DuplicatePolicy.REFRESH_EXPIRY -> {
                        unindex(it.id)
                        index(invitation)
                        duplicateMutation = DuplicateMutation(
                            existing = it,
                            result = SendResult.Refreshed(invitation.id),
                            fireCancel = false,
                            fireSend = false,
                        )
                    }
                }
                return@synchronized
            }
            maxPerInviter?.let { limit ->
                if (byInviter[invitation.inviterId].orEmpty().size >= limit) {
                    return SendResult.LimitReached(limit)
                }
            }
            maxPerInvited?.let { limit ->
                if (byInvited[invitation.invitedId].orEmpty().size >= limit) {
                    return SendResult.LimitReached(limit)
                }
            }
            index(invitation)
        }
        duplicateMutation?.let {
            applyDuplicateMutation(it, invitation, actor)
            return it.result
        }
        try {
            storeSave(invitation)
        } catch (e: StoreWriteFailedException) {
            synchronized(lock) { unindex(invitation.id) }
            return SendResult.StoreFailure(e.cause ?: e)
        }

        if (!scheduleExpiry(invitation)) return SendResult.Accepted(invitation.id)
        fireHook(invitation, InvitationAction.SENT, actor = actor) { handler.onSend(it) }
        return SendResult.Accepted(invitation.id)
    }

    /** Accept by id. @return `true` if it was accepted, `false` if not found or vetoed. */
    @JvmOverloads
    fun accept(invitationId: UUID, actor: ActorContext? = null): Boolean =
        acceptDetailed(invitationId, actor) is AcceptResult.Accepted

    /** Accept the invitation between this pair. @return `true` if one existed and was accepted. */
    @JvmOverloads
    fun accept(inviterId: UUID, invitedId: UUID, actor: ActorContext? = null): Boolean =
        acceptDetailed(inviterId, invitedId, actor) is AcceptResult.Accepted

    /** Deny by id. @return `true` if it was denied, `false` if not found or vetoed. */
    @JvmOverloads
    fun deny(invitationId: UUID, actor: ActorContext? = null): Boolean =
        denyDetailed(invitationId, actor) is DenyResult.Denied

    /** Cancel/revoke by id. @return `true` if it was cancelled, `false` if not found or vetoed. */
    @JvmOverloads
    fun cancel(invitationId: UUID, actor: ActorContext? = null): Boolean =
        cancelDetailed(invitationId, actor) is CancelResult.Cancelled

    /** Accept by id, returning a detailed [AcceptResult] that distinguishes not-found from vetoed. */
    @JvmOverloads
    fun acceptDetailed(invitationId: UUID, actor: ActorContext? = null): AcceptResult =
        when (consume(invitationId, InvitationAction.ACCEPTED, startsPairCooldown = false, actor = actor) { handler.onAccept(it) }) {
            ConsumeOutcome.CONSUMED -> AcceptResult.Accepted(invitationId)
            ConsumeOutcome.VETOED -> AcceptResult.Vetoed
            ConsumeOutcome.NOT_FOUND -> AcceptResult.NotFound
        }

    /** Accept the invitation between this pair, returning a detailed [AcceptResult]. */
    @JvmOverloads
    fun acceptDetailed(inviterId: UUID, invitedId: UUID, actor: ActorContext? = null): AcceptResult {
        val id = getInvite(inviterId, invitedId)?.id ?: return AcceptResult.NotFound
        return acceptDetailed(id, actor)
    }

    /** Deny by id, returning a detailed [DenyResult]. Starts a pair cooldown if one is configured. */
    @JvmOverloads
    fun denyDetailed(invitationId: UUID, actor: ActorContext? = null): DenyResult =
        when (consume(invitationId, InvitationAction.DENIED, startsPairCooldown = true, actor = actor) { handler.onDeny(it) }) {
            ConsumeOutcome.CONSUMED -> DenyResult.Denied(invitationId)
            ConsumeOutcome.VETOED -> DenyResult.Vetoed
            ConsumeOutcome.NOT_FOUND -> DenyResult.NotFound
        }

    /** Cancel/revoke by id (reason `REVOKED`), returning a detailed [CancelResult]. */
    @JvmOverloads
    fun cancelDetailed(invitationId: UUID, actor: ActorContext? = null): CancelResult =
        when (
            consume(invitationId, InvitationAction.CANCELLED, CancelReason.REVOKED, startsPairCooldown = true, actor = actor) {
                handler.onCancel(it, CancelReason.REVOKED)
            }
        ) {
            ConsumeOutcome.CONSUMED -> CancelResult.Cancelled(invitationId)
            ConsumeOutcome.VETOED -> CancelResult.Vetoed
            ConsumeOutcome.NOT_FOUND -> CancelResult.NotFound
        }

    /** Deny every pending invitation addressed to [invitedId]. @return how many were denied. */
    fun denyAll(invitedId: UUID): Int =
        consumeAll(byInvited[invitedId].orEmpty(), InvitationAction.DENIED) { handler.onDeny(it) }

    /** Cancel/revoke every pending invitation sent by [inviterId]. @return how many were cancelled. */
    fun cancelAllFrom(inviterId: UUID): Int =
        consumeAll(byInviter[inviterId].orEmpty(), InvitationAction.CANCELLED, CancelReason.REVOKED) {
            handler.onCancel(it, CancelReason.REVOKED)
        }

    /** Cancel every invitation involving [playerId] in either direction, with the given [reason]. */
    @JvmOverloads
    fun clearAllFor(playerId: UUID, reason: CancelReason = CancelReason.PARTY_CLEARED): Int =
        clearMatching(reason) { byInviter[playerId].orEmpty() + byInvited[playerId].orEmpty() }

    /** Cancel every invitation [playerId] *sent*, with the given [reason]. */
    @JvmOverloads
    fun clearAsInviter(playerId: UUID, reason: CancelReason = CancelReason.PARTY_CLEARED): Int =
        clearMatching(reason) { byInviter[playerId].orEmpty() }

    /** Cancel every invitation *addressed to* [playerId], with the given [reason]. */
    @JvmOverloads
    fun clearAsInvited(playerId: UUID, reason: CancelReason = CancelReason.PARTY_CLEARED): Int =
        clearMatching(reason) { byInvited[playerId].orEmpty() }

    /**
     * Administratively cancel one invitation by id, bypassing vetoes and recording reason
     * `ADMIN_CLEARED` for audit. @return [CancelResult.Cancelled] or [CancelResult.NotFound].
     */
    @JvmOverloads
    fun adminCancel(invitationId: UUID, admin: ActorContext = ActorContext.ADMIN): CancelResult {
        val invitation = synchronized(lock) {
            unindex(invitationId)?.also {
                recordCooldown(it.inviterId, it.invitedId)
                rateLimiter?.forget(it.inviterId, it.invitedId)
            }
        } ?: return CancelResult.NotFound
        cancelTimers(invitationId)
        storeRemove(invitationId)
        fireHook(invitation, InvitationAction.CANCELLED, CancelReason.ADMIN_CLEARED, actor = admin) {
            handler.onCancel(it, CancelReason.ADMIN_CLEARED)
        }
        return CancelResult.Cancelled(invitationId)
    }

    /** Like [clearAllFor] but with reason `ADMIN_CLEARED`, for admin tooling. */
    @JvmOverloads
    fun adminClearAllFor(playerId: UUID, admin: ActorContext = ActorContext.ADMIN): Int =
        clearMatching(CancelReason.ADMIN_CLEARED, actor = admin) { byInviter[playerId].orEmpty() + byInvited[playerId].orEmpty() }

    /** Both-directions clear alias (reason `PARTY_CLEARED`), kept for backward compatibility. */
    fun clearFor(playerId: UUID): Int = clearAllFor(playerId, CancelReason.PARTY_CLEARED)

    /** Pending invitations addressed to [invitedId], optionally [sort]ed. */
    @JvmOverloads
    fun getInvitesFor(invitedId: UUID, sort: SortOrder? = null): List<T> =
        byInvited[invitedId].orEmpty().mapNotNull { byId[it] }.sortedBy(sort)

    /** Pending invitations sent by [inviterId], optionally [sort]ed. */
    @JvmOverloads
    fun getInvitesFrom(inviterId: UUID, sort: SortOrder? = null): List<T> =
        byInviter[inviterId].orEmpty().mapNotNull { byId[it] }.sortedBy(sort)

    /** Pending invitations between two players in either direction, optionally [sort]ed. */
    @JvmOverloads
    fun getInvitesBetween(playerA: UUID, playerB: UUID, sort: SortOrder? = null): List<T> = synchronized(lock) {
        listOfNotNull(findPair(playerA, playerB), findPair(playerB, playerA))
    }.sortedBy(sort)

    /** Number of pending invitations addressed to [invitedId]. */
    fun countFor(invitedId: UUID): Int = byInvited[invitedId].orEmpty().size

    /** Number of pending invitations sent by [inviterId]. */
    fun countFrom(inviterId: UUID): Int = byInviter[inviterId].orEmpty().size

    /** Total number of pending invitations across all players. */
    fun pendingCount(): Int = byId.size

    /** The most recently created invitation addressed to [invitedId], or `null` if none. */
    fun getMostRecentFor(invitedId: UUID): T? =
        getInvitesFor(invitedId).maxByOrNull { it.createdAt }

    /** The pending invitation from [inviterId] to [invitedId], or `null` if none. */
    fun getInvite(inviterId: UUID, invitedId: UUID): T? = synchronized(lock) {
        findPair(inviterId, invitedId)
    }

    /** Look up a pending invitation by id, or `null` if not found. */
    operator fun get(invitationId: UUID): T? = byId[invitationId]

    /** All pending invitations, optionally [sort]ed. */
    @JvmOverloads
    fun all(sort: SortOrder? = null): List<T> = byId.values.toList().sortedBy(sort)

    /**
     * Load persisted invitations into memory, reconcile them per the configured [RehydratePolicy], and
     * re-arm expiry timers. Already-indexed invitations are skipped (safe to call twice). @return the
     * number of invitations still pending after reconciliation and inline expiry.
     */
    fun rehydrate(): Int {
        val loaded = guardStore("load") { store.load() }
        val (kept, dropped) = reconcileOnLoad(loaded)
        if (dropped.isNotEmpty() && rehydratePolicy.repairStore) {
            storeRemoveAll(dropped)
        }
        var stillPending = 0
        for (invitation in kept) {
            val freshlyIndexed = synchronized(lock) {
                if (byId.containsKey(invitation.id)) {
                    false
                } else { index(invitation); true }
            }
            if (!freshlyIndexed) continue
            if (scheduleExpiry(invitation)) stillPending++
        }
        return stillPending
    }

    /**
     * Expire every currently-indexed invitation whose `expiresAt` has already passed, firing `onExpire`
     * for each, and return how many were expired.
     *
     * Invitations normally expire on their own scheduled timer. This is the bulk path for the cases a
     * per-invite timer cannot cover: invites that lapsed while the server was **down** (their timers
     * never ran), or a periodic sweep that backstops a flaky scheduler. It consults
     * [InvitationStore.loadExpired] so a SQL store can answer with a direct `WHERE expires_at <= ?`
     * query instead of scanning everything, then expires the intersection that is live in memory.
     *
     * Call this once after [rehydrate] at startup, and optionally on an interval, to enforce the
     * "expire on next startup / while running" semantics (see the class KDoc on downtime expiry).
     */
    fun sweepExpired(): Int {
        val now = clock.now()
        // Prefer the store's targeted query; fall back to the in-memory view if it cannot answer.
        val candidateIds = try {
            guardStore("loadExpired") { store.loadExpired(now) }.mapTo(HashSet()) { it.id }
        } catch (_: Throwable) {
            byId.values.filter { it.expiresAt != null && it.expiresAt!! <= now }.mapTo(HashSet()) { it.id }
        }
        val expired = synchronized(lock) {
            candidateIds.mapNotNull { id ->
                val inv = byId[id] ?: return@mapNotNull null
                if (inv.expiresAt == null || inv.expiresAt!! > now) return@mapNotNull null
                unindex(id)?.also { recordCooldown(it.inviterId, it.invitedId) }
            }
        }
        storeRemoveAll(expired.map { it.id })
        expired.forEach { invitation ->
            cancelTimers(invitation.id)
            fireHook(invitation, InvitationAction.EXPIRED) { handler.onExpire(it) }
        }
        return expired.size
    }

    private fun reconcileOnLoad(loaded: List<T>): Pair<List<T>, List<UUID>> {
        if (rehydratePolicy == RehydratePolicy.TRUST_STORE) return loaded to emptyList()
        val dropped = mutableListOf<UUID>()
        var rows = loaded

        if (rehydratePolicy.dropDuplicateIds) {
            val seen = HashSet<UUID>()
            rows = rows.filter {
                if (seen.add(it.id)) {
                    true
                } else { dropped += it.id; false }
            }
        }
        if (rehydratePolicy.dropDuplicatePairs) {
            val byPair = HashMap<Pair<UUID, UUID>, T>()
            val keep = LinkedHashSet<UUID>()
            // If a pair was written twice, the newest row is the one players most likely saw.
            for (inv in rows.sortedByDescending { it.createdAt }) {
                val key = inv.inviterId to inv.invitedId
                if (byPair.putIfAbsent(key, inv) == null) keep += inv.id else dropped += inv.id
            }
            rows = rows.filter { it.id in keep }
        }
        if (rehydratePolicy.enforceCaps) {
            rows = enforceCapOnLoad(rows, maxPerInviter, dropped) { it.inviterId }
            rows = enforceCapOnLoad(rows, maxPerInvited, dropped) { it.invitedId }
        }
        return rows to dropped
    }
    private inline fun enforceCapOnLoad(rows: List<T>, cap: Int?, dropped: MutableList<UUID>, keyOf: (T) -> UUID): List<T> {
        if (cap == null) return rows
        val counts = HashMap<UUID, Int>()
        val keep = LinkedHashSet<UUID>()
        for (inv in rows.sortedByDescending { it.createdAt }) {
            val key = keyOf(inv)
            val n = counts.getOrDefault(key, 0)
            if (n < cap) { counts[key] = n + 1; keep += inv.id } else {
                dropped += inv.id
            }
        }
        return rows.filter { it.id in keep }
    }
    /**
     * Cancel all expiry/warning timers and close the backing [InvitationStore]. Call on plugin
     * disable. Does not fire cancel hooks for the still-pending invitations — they remain persisted and
     * reappear on the next [rehydrate].
     */
    fun shutdown() {
        timers.values.forEach { it.cancel() }
        timers.clear()
        warningTimers.values.forEach { batch -> batch.forEach { it.cancel() } }
        warningTimers.clear()
        try {
            store.close()
        } catch (t: Throwable) {
            logger.error("invitation store close failed", t)
        }
    }

    private fun applyDuplicateMutation(mutation: DuplicateMutation<T>, invitation: T, actor: ActorContext?) {
        cancelTimers(mutation.existing.id)
        storeReplace(mutation.existing.id, invitation)
        if (mutation.fireCancel) {
            fireHook(mutation.existing, InvitationAction.CANCELLED, CancelReason.DUPLICATE_REPLACED, actor = actor) {
                handler.onCancel(it, CancelReason.DUPLICATE_REPLACED)
            }
        }
        notifyObservers(invitation, InvitationAction.REPLACED, replacedId = mutation.existing.id, actor = actor)
        if (scheduleExpiry(invitation) && mutation.fireSend) {
            fireHook(invitation, InvitationAction.SENT, actor = actor) { handler.onSend(it) }
        }
    }
    private enum class ConsumeOutcome { CONSUMED, NOT_FOUND, VETOED }
    private fun consume(
        invitationId: UUID,
        action: InvitationAction,
        cancelReason: CancelReason? = null,
        startsPairCooldown: Boolean,
        actor: ActorContext? = null,
        hook: (T) -> Unit,
    ): ConsumeOutcome {
        val peeked = byId[invitationId] ?: return ConsumeOutcome.NOT_FOUND
        if (isVetoed(peeked, action, cancelReason)) return ConsumeOutcome.VETOED

        val invitation = synchronized(lock) {
            unindex(invitationId)?.also {
                if (startsPairCooldown) recordCooldown(it.inviterId, it.invitedId)
                rateLimiter?.forget(it.inviterId, it.invitedId)
            }
        } ?: return ConsumeOutcome.NOT_FOUND
        cancelTimers(invitationId)
        storeRemove(invitationId)
        fireHook(invitation, action, cancelReason, actor, hook)
        return ConsumeOutcome.CONSUMED
    }
// Store writes all pass through here so the selected failure policy behaves consistently.
    private inline fun storeWrite(op: String, write: () -> Unit) {
        val attempts = when (storeFailurePolicy) {
            StoreFailurePolicy.FAIL_BEFORE_MUTATING -> 1
            else -> storeWriteRetries + 1
        }
        var last: Throwable? = null
        repeat(attempts) { attempt ->
            try {
                write()
                return
            } catch (t: Throwable) {
                last = t
                metrics.recordStoreFailure()
                logger.error("invitation store $op failed (attempt ${attempt + 1}/$attempts)", t)
            }
        }
        val failure = last ?: return
        when (storeFailurePolicy) {
            StoreFailurePolicy.FAIL_BEFORE_MUTATING ->
                throw StoreWriteFailedException("invitation store $op failed", failure)
            StoreFailurePolicy.MUTATE_THEN_RETRY -> Unit
            StoreFailurePolicy.MARK_UNHEALTHY -> {
                healthy = false
                logger.error("invitation manager marked unhealthy after store $op failed", failure)
            }
        }
    }

    private fun storeSave(invitation: T) = storeWrite("save") { store.save(invitation) }

    private fun storeRemove(id: UUID) = storeWrite("remove") { store.remove(id) }
    private fun storeRemoveAll(ids: Collection<UUID>) {
        if (ids.isEmpty()) return
        storeWrite("removeAll") { store.removeAll(ids) }
    }
    private fun storeReplace(old: UUID, new: T) = storeWrite("replace") { store.replace(old, new) }

    private inline fun <R> guardStore(op: String, block: () -> R): R {
        try {
            return block()
        } catch (t: Throwable) {
            metrics.recordStoreFailure()
            logger.error("invitation store $op failed", t)
            throw t
        }
    }
    private fun consumeAll(
        ids: Set<UUID>,
        action: InvitationAction,
        cancelReason: CancelReason? = null,
        actor: ActorContext? = null,
        hook: (T) -> Unit,
    ): Int {
        val removed = synchronized(lock) {
            ids.toList().mapNotNull { id ->
                unindex(id)?.also { recordCooldown(it.inviterId, it.invitedId) }
            }
        }
        storeRemoveAll(removed.map { it.id })
        removed.forEach { invitation ->
            cancelTimers(invitation.id)
            fireHook(invitation, action, cancelReason, actor, hook)
        }
        return removed.size
    }
    private fun clearMatching(reason: CancelReason, actor: ActorContext? = null, select: () -> Set<UUID>): Int {
        val cleared = synchronized(lock) {
            select().mapNotNull { id ->
                unindex(id)?.also { recordCooldown(it.inviterId, it.invitedId) }
            }
        }
        storeRemoveAll(cleared.map { it.id })
        cleared.forEach { invitation ->
            cancelTimers(invitation.id)
            fireHook(invitation, InvitationAction.CANCELLED, reason, actor) { handler.onCancel(it, reason) }
        }
        return cleared.size
    }
    private fun cancelTimers(invitationId: UUID) {
        timers.remove(invitationId)?.cancel()
        warningTimers.remove(invitationId)?.forEach { it.cancel() }
    }
    private fun scheduleExpiry(invitation: T): Boolean {
        val expiresAt = invitation.expiresAt ?: return true
        val delay = expiresAt - scheduler.now()
        if (delay <= 0) {
            val live = synchronized(lock) {
                unindex(invitation.id)?.also { recordCooldown(it.inviterId, it.invitedId) }
            }
            if (live != null) {
                storeRemove(live.id)
                fireHook(live, InvitationAction.EXPIRED) { handler.onExpire(it) }
            }
            return false
        }
        timers[invitation.id] = scheduler.runLater(delay) {
            val live = synchronized(lock) {
                unindex(invitation.id)?.also { recordCooldown(it.inviterId, it.invitedId) }
            } ?: return@runLater
            cancelTimers(invitation.id)
            storeRemove(live.id)
            fireHook(live, InvitationAction.EXPIRED) { handler.onExpire(it) }
        }
        scheduleExpiryWarnings(invitation, expiresAt)
        return true
    }
// Warning timers are best-effort. If the invite is gone when one fires, there is nothing to warn about.
    private fun scheduleExpiryWarnings(invitation: T, expiresAt: Long) {
        if (expiryWarningOffsetsMillis.isEmpty()) return
        for (offset in expiryWarningOffsetsMillis) {
            if (offset <= 0) continue
            val warnAt = expiresAt - offset
            val delay = warnAt - scheduler.now()
            if (delay <= 0) continue
            val timer = scheduler.runLater(delay) {
                if (!byId.containsKey(invitation.id)) return@runLater
                val remaining = (expiresAt - scheduler.now()).coerceAtLeast(0)
                dispatch { guarded(invitation, InvitationAction.EXPIRED) { handler.onExpiryWarning(invitation, remaining) } }
            }
            warningTimers.getOrPut(invitation.id) { mutableListOf() }.add(timer)
        }
    }

    private fun dispatch(block: () -> Unit) = scheduler.runOnMainThread(block)
    private fun isVetoed(invitation: T, action: InvitationAction, cancelReason: CancelReason? = null): Boolean {
        for (veto in vetoes) if (veto.isVetoed(invitation, action, cancelReason)) return true
        return false
    }
    private fun notifyObservers(
        invitation: T,
        action: InvitationAction,
        cancelReason: CancelReason? = null,
        replacedId: UUID? = null,
        actor: ActorContext? = null,
    ) {
        if (observers.isEmpty()) return
        val event = LifecycleEvent(invitation, action, clock.now(), cancelReason, replacedId, actor)
        dispatch {
            for (observer in observers) {
                guarded(invitation, action) { observer.onEvent(event) }
            }
        }
    }
    private inline fun guarded(invitation: T, action: InvitationAction, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            // Plugin callbacks are isolated by default; server owners can opt into propagation.
            logger.error("invitation lifecycle callback for $action threw on id=${invitation.id}", t)
            try {
                errorCallback.onLifecycleError(invitation, action, t)
            } catch (_: Throwable) {
            }
            if (errorPolicy == LifecycleErrorPolicy.PROPAGATE) throw t
        }
    }
    private fun fireHook(
        invitation: T,
        action: InvitationAction,
        cancelReason: CancelReason? = null,
        actor: ActorContext? = null,
        hook: (T) -> Unit,
    ) {
        dispatch { guarded(invitation, action) { hook(invitation) } }
        notifyObservers(invitation, action, cancelReason, actor = actor)
    }
    private fun index(invitation: T) {
        byId[invitation.id] = invitation
        byInvited.getOrPut(invitation.invitedId) { mutableSetOf() }.add(invitation.id)
        byInviter.getOrPut(invitation.inviterId) { mutableSetOf() }.add(invitation.id)
    }

    private fun unindex(invitationId: UUID): T? {
        val invitation = byId.remove(invitationId) ?: return null
        byInvited[invitation.invitedId]?.apply { remove(invitationId); if (isEmpty()) byInvited.remove(invitation.invitedId) }
        byInviter[invitation.inviterId]?.apply { remove(invitationId); if (isEmpty()) byInviter.remove(invitation.inviterId) }
        return invitation
    }
    private fun List<T>.sortedBy(sort: SortOrder?): List<T> = when (sort) {
        null -> this
        SortOrder.NEWEST_FIRST -> sortedByDescending { it.createdAt }
        SortOrder.OLDEST_FIRST -> sortedBy { it.createdAt }
    }

    private fun findPair(inviterId: UUID, invitedId: UUID): T? =
        byInviter[inviterId]
            .orEmpty()
            .asSequence()
            .mapNotNull { byId[it] }
            .firstOrNull { it.invitedId == invitedId }

    private fun activeCooldownRemaining(inviterId: UUID, invitedId: UUID): Long? {
        val key = PairKey(inviterId, invitedId)
        val until = pairCooldownUntil[key] ?: return null
        val remaining = until - clock.now()
        if (remaining <= 0) {
            pairCooldownUntil.remove(key, until)
            return null
        }
        return remaining
    }

    private fun recordCooldown(inviterId: UUID, invitedId: UUID) {
        val millis = pairCooldownMillis?.takeIf { it > 0 } ?: return
        pairCooldownUntil[PairKey(inviterId, invitedId)] = clock.now() + millis
    }

    private data class PairKey(val inviterId: UUID, val invitedId: UUID)

    private data class DuplicateMutation<T : Invitation>(
        val existing: T,
        val result: SendResult,
        val fireCancel: Boolean,
        val fireSend: Boolean,
    )

    companion object {
        /** Entry point for the Java-friendly [Builder]; [handler] and [scheduler] are required. */
        @JvmStatic
        fun <T : Invitation> builder(
            handler: InvitationHandler<T>,
            scheduler: Scheduler,
        ): Builder<T> = Builder(handler, scheduler)
    }

    /**
     * Fluent, Java-friendly builder for [InvitationManager]. Every option has a safe default, so new
     * options can be added without breaking callers. Obtain one via [InvitationManager.builder].
     */
    class Builder<T : Invitation> internal constructor(
        private val handler: InvitationHandler<T>,
        private val scheduler: Scheduler,
    ) {
        private var maxPerInviter: Int? = null
        private var maxPerInvited: Int? = null
        private var pairCooldownMillis: Long? = null
        private var duplicatePolicy: DuplicatePolicy = DuplicatePolicy.REJECT_EXISTING
        private var store: InvitationStore<T> = InvitationStore.InMemory()
        private var selfInvitePolicy: SelfInvitePolicy = SelfInvitePolicy.REJECT
        private val validationPolicies = mutableListOf<ValidationPolicy<T>>()
        private var rateLimiter: RateLimiter? = null
        private var expiryWarningOffsetsMillis: List<Long> = emptyList()
        private val observers = mutableListOf<InvitationObserver<T>>()
        private val vetoes = mutableListOf<InvitationVeto<T>>()
        private var logger: InvitationLogger = InvitationLogger.Noop
        private var metrics: InvitationMetrics = InvitationMetrics.Noop
        private var audit: InvitationAudit = InvitationAudit.Noop
        private var errorPolicy: LifecycleErrorPolicy = LifecycleErrorPolicy.ISOLATE
        private var errorCallback: LifecycleErrorCallback<T> = LifecycleErrorCallback.noop()
        private var storeFailurePolicy: StoreFailurePolicy = StoreFailurePolicy.FAIL_BEFORE_MUTATING
        private var storeWriteRetries: Int = 2
        private var rehydratePolicy: RehydratePolicy = RehydratePolicy.REPAIR
        private var requireExpiry: Boolean = false
        private var maxExpiryMillis: Long? = null
        private var clock: Clock? = null
        /** Cap simultaneous pending invitations *from* one inviter. `null` = unlimited. */
        fun maxPerInviter(limit: Int?): Builder<T> = apply { this.maxPerInviter = limit }

        /** Cap simultaneous pending invitations *to* one invited player. `null` = unlimited. */
        fun maxPerInvited(limit: Int?): Builder<T> = apply { this.maxPerInvited = limit }
/**
         * Cooldown applied to an (inviter, invited) pair after a deny, cancel, expire, or duplicate
         * attempt, blocking re-sends with [SendResult.CooldownActive] until it elapses.
         *
         * Runtime-only: cooldowns are held in memory and are never persisted, so they are cleared on
         * every restart. Treat this as anti-spam friction, not a durable ban — for a cooldown that
         * survives restarts, back a [ValidationPolicy] with your own durable store instead.
         */
        fun pairCooldownMillis(millis: Long?): Builder<T> = apply { this.pairCooldownMillis = millis }
        /** How to handle a second invite for an existing pair. See [DuplicatePolicy]. */
        fun duplicatePolicy(policy: DuplicatePolicy): Builder<T> = apply { this.duplicatePolicy = policy }

        /** Whether inviter == invited is rejected or allowed. See [SelfInvitePolicy]. */
        fun selfInvitePolicy(policy: SelfInvitePolicy): Builder<T> = apply { this.selfInvitePolicy = policy }

        /** Add a [ValidationPolicy] run on every send; may be called repeatedly to chain rules. */
        fun validationPolicy(policy: ValidationPolicy<T>): Builder<T> = apply { this.validationPolicies += policy }

        /** Set a pre-built [RateLimiter]. `null` disables rate limiting. */
        fun rateLimiter(limiter: RateLimiter?): Builder<T> = apply { this.rateLimiter = limiter }

        /** Convenience for building a [RateLimiter] from per-inviter/invited/pair [RateLimiter.Limit]s. */
        fun rateLimits(
            perInviter: RateLimiter.Limit? = null,
            perInvited: RateLimiter.Limit? = null,
            perPair: RateLimiter.Limit? = null,
        ): Builder<T> = apply {
            this.rateLimiter = RateLimiter(perInviter, perInvited, perPair) { scheduler.now() }
        }

        /** Offsets-before-expiry (in millis) at which [InvitationHandler.onExpiryWarning] fires. */
        fun expiryWarningOffsetsMillis(vararg offsetsMillis: Long): Builder<T> =
            apply { this.expiryWarningOffsetsMillis = offsetsMillis.toList() }

        /** Set the persistence backend. Defaults to [InvitationStore.InMemory]. */
        fun store(store: InvitationStore<T>): Builder<T> = apply { this.store = store }

        /** Register a lifecycle [InvitationObserver]; may be called repeatedly. */
        fun observer(observer: InvitationObserver<T>): Builder<T> = apply { this.observers += observer }

        /** Register a pre-mutation [InvitationVeto]; may be called repeatedly. */
        fun veto(veto: InvitationVeto<T>): Builder<T> = apply { this.vetoes += veto }

        /** Attach a logger; wired internally as a [LoggingObserver]. */
        fun logger(logger: InvitationLogger): Builder<T> = apply { this.logger = logger }

        /** Attach a metrics sink; wired internally as a [MetricsObserver]. */
        fun metrics(metrics: InvitationMetrics): Builder<T> = apply { this.metrics = metrics }

        /** Attach an audit sink; wired internally as an [AuditObserver]. */
        fun audit(audit: InvitationAudit): Builder<T> = apply { this.audit = audit }

        /** Whether callback exceptions are isolated or propagated. See [LifecycleErrorPolicy]. */
        fun errorPolicy(policy: LifecycleErrorPolicy): Builder<T> = apply { this.errorPolicy = policy }

        /** Callback invoked whenever a hook/observer throws, regardless of [errorPolicy]. */
        fun errorCallback(callback: LifecycleErrorCallback<T>): Builder<T> = apply { this.errorCallback = callback }

        /** How store write failures are handled. See [StoreFailurePolicy]. */
        fun storeFailurePolicy(policy: StoreFailurePolicy): Builder<T> = apply { this.storeFailurePolicy = policy }

        /** Retry attempts for store writes under retrying failure policies. */
        fun storeWriteRetries(retries: Int): Builder<T> = apply { this.storeWriteRetries = retries }

        /** How loaded rows are reconciled on [rehydrate]. See [RehydratePolicy]. */
        fun rehydratePolicy(policy: RehydratePolicy): Builder<T> = apply { this.rehydratePolicy = policy }
/**
         * When `true`, [send] rejects invitations that have no `expiresAt` with
         * [SendResult.ExpiryRequired]. Defaults to `false` so no-expiry (permanent) invitations remain
         * allowed; production callers that never want a pending invite to live forever should enable it.
         */
        fun requireExpiry(require: Boolean): Builder<T> = apply { this.requireExpiry = require }
/**
         * Cap an invitation's lifetime (from now to its `expiresAt`). Sends asking for a longer life are
         * rejected with [SendResult.ExpiryTooLong]. `null` (default) means no cap.
         */
        fun maxExpiry(duration: java.time.Duration?): Builder<T> =
            apply { this.maxExpiryMillis = duration?.toMillis() }
/**
         * Override the engine clock. Defaults to one derived from the [scheduler], which is almost always
         * what you want; supply a fixed clock only in tests that drive time manually.
         */
        fun clock(clock: Clock?): Builder<T> = apply { this.clock = clock }

        /** Construct the configured [InvitationManager]. */
        fun build(): InvitationManager<T> =
            InvitationManager(
                handler,
                scheduler,
                maxPerInviter,
                store,
                maxPerInvited,
                pairCooldownMillis,
                duplicatePolicy,
                selfInvitePolicy,
                validationPolicies.toList(),
                rateLimiter,
                expiryWarningOffsetsMillis,
                observers.toList(),
                vetoes.toList(),
                logger,
                metrics,
                audit,
                errorPolicy,
                errorCallback,
                storeFailurePolicy,
                storeWriteRetries,
                rehydratePolicy,
                requireExpiry,
                maxExpiryMillis,
                clock ?: Clock.fromScheduler(scheduler),
            )
    }
}
