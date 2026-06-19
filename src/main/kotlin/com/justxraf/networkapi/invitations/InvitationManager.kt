package com.justxraf.networkapi.invitations

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The reusable invitations engine — one instance per invitation kind (island invites, teleport
 * requests, …). It owns everything identical across plugins: a registry by id, reverse indexes by
 * invited and by inviter, de-duplication of (inviter, invited) pairs, optional per-inviter and
 * per-invited caps, and one cancellable expiry timer per invitation. Meaning is delegated to the
 * [InvitationHandler].
 *
 * Thread-safety: the maps are concurrent and pair-mutating operations are guarded by [lock] so the
 * three indexes never drift apart. Handler callbacks are dispatched via [Scheduler.runOnMainThread].
 *
 * Concurrency contract (see also §4 of the roadmap and `ConcurrencyTest`):
 *  - **Indexes are mutated only under [lock].** [index], [unindex] and [findPair] are never called
 *    outside a `synchronized(lock)` block; this is what keeps [byId], [byInvited] and [byInviter] in
 *    agreement under contention. The `ConcurrentHashMap`s allow lock-free *reads* (`get`/snapshot
 *    queries like [all] / [getInvitesFor]) but every *write* path takes the lock.
 *  - **Callbacks never run while [lock] is held.** Every handler hook ([InvitationHandler]) and
 *    observer ([InvitationObserver]) is dispatched through [Scheduler.runOnMainThread] *after* the
 *    synchronized mutation has returned, so a callback can freely call back into the manager without
 *    self-deadlocking, and a slow callback never blocks other threads' mutations.
 *  - **Callbacks are scheduled, not synchronous with the result.** [send]/[accept]/[deny]/[cancel]
 *    commit state and return their typed result immediately; the corresponding hook/observer runs on
 *    the next main-thread turn. Do not assume a hook has fired by the time the call returns. (Vetoes
 *    are the exception: they are consulted *inline before* the mutation, on the calling thread.)
 *  - **Each lifecycle transition fires exactly once.** Terminal actions remove the invite from [byId]
 *    under the lock before firing, so concurrent accept/deny/cancel/expire race to a single winner —
 *    the losers see the id already gone and become no-ops ([AcceptResult.NotFound] etc.). An invite is
 *    therefore announced once as `SENT` and once as exactly one of accepted/denied/cancelled/expired.
 *
 * The generic [T] flows back to the caller, so e.g. [getInvitesFor] returns `IslandInvite`, never a
 * bare [Invitation].
 *
 * Durability is delegated to an [InvitationStore]: the manager writes through on every mutation and,
 * on startup, [rehydrate] reloads the store and re-arms expiry. The default in-memory store keeps the
 * original "vanishes on restart" behaviour; pass a file/SQL-backed store to survive restarts.
 *
 * @param maxPerInviter cap on simultaneous pending invites from one inviter, or `null` for unlimited.
 * @param store durability backend; defaults to a volatile in-memory store (no cross-restart persistence).
 * @param maxPerInvited cap on simultaneous pending invites addressed to one player, or `null` for unlimited.
 * @param pairCooldownMillis runtime-only cooldown applied to one inviter/invited pair after deny,
 * cancel, expire, or a duplicate send attempt. `null` or `0` disables cooldowns.
 *
 * **Cooldown & rate-limit durability:** both the per-pair cooldown ([pairCooldownMillis]) and the
 * [rateLimiter] keep their state purely in memory. They are **runtime-only**: a server restart clears
 * them, so the very first action after a restart is never throttled, even when the backing [store]
 * persists the invitations themselves. This is intentional — abuse windows are short relative to
 * downtime, and persisting them would couple every send to a store write. Plugins that need cooldowns
 * to survive restarts should persist their own last-action timestamps and seed a custom
 * [ValidationPolicy] from them on startup.
 *
 * @param validationPolicies abuse/permission/state guards ([ValidationPolicy]) run before [send]
 * indexes an invite, in order; the first non-null [RejectionReason] aborts with [SendResult.PolicyRejected].
 * @param rateLimiter optional send-volume limiter (per inviter / invited / pair); breaches abort with
 * [SendResult.RateLimited]. Runtime-only (see the durability note above). Admin sends bypass it.
 * @param duplicatePolicy how [send] handles a new invitation for a pair that already has one pending.
 * @param selfInvitePolicy how [send] handles an invitation whose inviter equals its invited player.
 * @param expiryWarningOffsetsMillis offsets *before* [Invitation.expiresAt] at which to fire
 * [InvitationHandler.onExpiryWarning]; e.g. `[10_000, 5_000]` warns 10s and 5s before expiry. Offsets
 * that fall at or after the expiry, or in the past for an already-short invite, are skipped.
 * @param observers passive post-transition listeners ([InvitationObserver]) notified once per
 * successful lifecycle transition, on the main thread, after state and store are committed. The
 * configured [logger], [metrics], and [audit] are wired in as observers automatically.
 * @param vetoes pre-transition veto hooks ([InvitationVeto]) consulted before accept/deny/cancel
 * mutate state; any returning true aborts the action with a `Vetoed` result. (Send-time vetoing is
 * [InvitationHandler.validate].)
 * @param logger diagnostics sink; one line per transition plus errors. Defaults to [InvitationLogger.Noop].
 * @param metrics counter sink for outcomes and store failures. Defaults to [InvitationMetrics.Noop].
 * @param audit structured audit sink, one [AuditEntry] per transition. Defaults to [InvitationAudit.Noop].
 * @param errorPolicy what to do when a handler hook or observer throws. Defaults to [LifecycleErrorPolicy.ISOLATE].
 * @param errorCallback notified (with full context) whenever a callback throws, regardless of [errorPolicy].
 * @param storeFailurePolicy how to react when the [store] throws on a write. Defaults to
 * [StoreFailurePolicy.FAIL_BEFORE_MUTATING] — the safest option: persist before mutating memory so
 * the two never diverge.
 * @param storeWriteRetries for [StoreFailurePolicy.MUTATE_THEN_RETRY] / [StoreFailurePolicy.MARK_UNHEALTHY],
 * how many extra attempts a write gets before it's considered failed. Ignored by
 * [StoreFailurePolicy.FAIL_BEFORE_MUTATING]. Defaults to 2.
 * @param rehydratePolicy how [rehydrate] treats duplicate ids, duplicate pairs, and cap overflows in
 * the loaded set. Defaults to [RehydratePolicy.REPAIR].
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
) {
    private val byId = ConcurrentHashMap<UUID, T>()
    private val byInvited = ConcurrentHashMap<UUID, MutableSet<UUID>>()
    private val byInviter = ConcurrentHashMap<UUID, MutableSet<UUID>>()
    private val timers = ConcurrentHashMap<UUID, Scheduler.Cancellable>()
    private val warningTimers = ConcurrentHashMap<UUID, MutableList<Scheduler.Cancellable>>()
    private val pairCooldownUntil = ConcurrentHashMap<PairKey, Long>()
    private val lock = Any()

    @Volatile private var healthy = true

    /**
     * False once a store write has persistently failed under [StoreFailurePolicy.MARK_UNHEALTHY]. The
     * in-memory state may have drifted from the store; callers/health checks can stop accepting new
     * invites until the backing store recovers. Always true under the other failure policies.
     */
    fun isHealthy(): Boolean = healthy

    /**
     * All post-transition observers: the caller-supplied [observers] plus the built-in logging,
     * metrics, and audit observers (only when their sinks are non-Noop, so the common no-op path stays
     * allocation- and call-free). Order: user observers first, then logging/metrics/audit.
     */
    private val observers: List<InvitationObserver<T>> = buildList {
        addAll(observers)
        if (logger !== InvitationLogger.Noop) add(LoggingObserver(logger))
        if (metrics !== InvitationMetrics.Noop) add(MetricsObserver(metrics))
        if (audit !== InvitationAudit.Noop) add(AuditObserver(audit))
    }

    /** Outcome of [send]. */
    sealed interface SendResult {
        data class Accepted(val invitationId: UUID) : SendResult
        /** A duplicate (same inviter → same invited) already exists. */
        data class Duplicate(val existingId: UUID) : SendResult
        /** A duplicate was handled by consuming [replacedId] and registering [invitationId]. */
        data class Replaced(val replacedId: UUID, val invitationId: UUID) : SendResult
        /** A duplicate was handled by refreshing the existing logical invitation. */
        data class Refreshed(val invitationId: UUID) : SendResult
        /** Rejected by [InvitationHandler.validate]; carries the reason it returned. */
        data class Rejected(val reason: String) : SendResult
        /**
         * Rejected by a [ValidationPolicy] with a typed, localizable [RejectionReason] — preferred over
         * the free-form [Rejected] for new code. (The legacy [InvitationHandler.validate] still maps to
         * [Rejected].)
         */
        data class PolicyRejected(val reason: RejectionReason) : SendResult
        /** Blocked by the configured [RateLimiter]; [retryAfterMillis] is when a retry may succeed. */
        data class RateLimited(val retryAfterMillis: Long) : SendResult
        /** The inviter or invited player is already at its configured pending invite cap. */
        data class LimitReached(val limit: Int) : SendResult
        /** The same inviter/invited pair is cooling down after a previous terminal or duplicate attempt. */
        data class CooldownActive(val remainingMillis: Long) : SendResult
        /** The inviter and invited player are the same and [SelfInvitePolicy.REJECT] is in effect. */
        data object SelfInvite : SendResult
        /** A registered [InvitationVeto] (e.g. a cancelled `InvitationSendEvent`) blocked the send. */
        data object Vetoed : SendResult
        /**
         * The store rejected the write under [StoreFailurePolicy.FAIL_BEFORE_MUTATING], so nothing was
         * registered — the in-memory state is unchanged. Carries the underlying [cause].
         */
        data class StoreFailure(val cause: Throwable) : SendResult
    }

    /**
     * Thrown by terminal/bulk operations when a store write fails under
     * [StoreFailurePolicy.FAIL_BEFORE_MUTATING]. ([send] reports the failure as
     * [SendResult.StoreFailure] instead, since it already has a typed result.)
     */
    class StoreWriteFailedException(message: String, cause: Throwable) : RuntimeException(message, cause)

    /**
     * Register an invitation: run [InvitationHandler.validate] and any [InvitationVeto] for
     * [InvitationAction.SENT], reject exact duplicates, enforce [maxPerInviter] and [maxPerInvited],
     * then store it, schedule expiry, and fire [InvitationHandler.onSend]. The returned [SendResult]
     * is reported to [InvitationMetrics.recordSendOutcome] for every outcome.
     *
     * Note on validation: [InvitationHandler.validate] runs **outside** [lock] (so a handler can do
     * blocking work — name lookups, permission checks — without stalling other threads), against a
     * *snapshot* of the inviter's existing invites taken at call time. It is therefore **advisory**:
     * between `validate` returning and the mutation acquiring the lock, another thread may have changed
     * the state it inspected. The authoritative checks — duplicate pair, [maxPerInviter] /
     * [maxPerInvited] caps, cooldown — are all re-evaluated *inside* the lock, so two racing sends can
     * never both slip past a cap or both register the same pair regardless of what `validate` saw.
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
        synchronized(lock) {
            activeCooldownRemaining(invitation.inviterId, invitation.invitedId)?.let {
                return SendResult.CooldownActive(it)
            }
            if (duplicatePolicy == DuplicatePolicy.REJECT_EXISTING) findPair(invitation.inviterId, invitation.invitedId)?.let {
                recordCooldown(invitation.inviterId, invitation.invitedId)
                return SendResult.Duplicate(it.id)
            }
        }

        // Typed validation policies (§6 abuse/permission guards) run before the legacy free-form
        // validate. Both run outside the lock and are advisory; authoritative caps/dupes are re-checked
        // in-lock below. Admin contexts may still hit a policy that doesn't honour [ActorContext.admin].
        for (policy in validationPolicies) {
            policy.validate(invitation, getInvitesFrom(invitation.inviterId), actor)?.let {
                return SendResult.PolicyRejected(it)
            }
        }
        handler.validate(invitation, getInvitesFrom(invitation.inviterId))?.let {
            return SendResult.Rejected(it)
        }
        // Cancellable pre-event hook (e.g. InvitationSendEvent), run before any mutation.
        if (isVetoed(invitation, InvitationAction.SENT)) return SendResult.Vetoed

        // Send-volume rate limit (distinct from the per-pair cooldown). Checked after vetoes/validation
        // so a rejected send doesn't consume the limiter budget; admins bypass it.
        if (actor?.admin != true) rateLimiter?.let { limiter ->
            when (val d = limiter.tryAcquire(invitation.inviterId, invitation.invitedId)) {
                is RateLimiter.Decision.Limited -> return SendResult.RateLimited(d.retryAfterMillis)
                RateLimiter.Decision.Allowed -> Unit
            }
        }

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

        // Transactional write: persist the freshly-indexed invitation. Under FAIL_BEFORE_MUTATING a
        // store failure must leave no trace, so roll the index back out before reporting it. (Under the
        // retry / unhealthy policies storeSave never throws — memory is the live truth by design.)
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

    /** The invited party accepts. No-op (returns false) if the id is unknown or already consumed. */
    @JvmOverloads
    fun accept(invitationId: UUID, actor: ActorContext? = null): Boolean =
        acceptDetailed(invitationId, actor) is AcceptResult.Accepted

    /**
     * Accept the invitation from [inviterId] to [invitedId] — the "accept from a name" path, where the
     * UX has the invited player name their inviter rather than quote an opaque id. No-op (returns
     * false) if no such pending invitation exists.
     */
    @JvmOverloads
    fun accept(inviterId: UUID, invitedId: UUID, actor: ActorContext? = null): Boolean =
        acceptDetailed(inviterId, invitedId, actor) is AcceptResult.Accepted

    /** The invited party declines. No-op (returns false) if the id is unknown or already consumed. */
    @JvmOverloads
    fun deny(invitationId: UUID, actor: ActorContext? = null): Boolean =
        denyDetailed(invitationId, actor) is DenyResult.Denied

    /** The inviter revokes it. No-op (returns false) if the id is unknown or already consumed. */
    @JvmOverloads
    fun cancel(invitationId: UUID, actor: ActorContext? = null): Boolean =
        cancelDetailed(invitationId, actor) is CancelResult.Cancelled

    /**
     * Idempotent [accept]: returns [AcceptResult.Accepted] on the transition, or [AcceptResult.NotFound]
     * if the id was unknown or already consumed — so a double-click / retry is distinguishable from a
     * fresh accept rather than collapsing to `false`.
     */
    @JvmOverloads
    fun acceptDetailed(invitationId: UUID, actor: ActorContext? = null): AcceptResult =
        when (consume(invitationId, InvitationAction.ACCEPTED, startsPairCooldown = false, actor = actor) { handler.onAccept(it) }) {
            ConsumeOutcome.CONSUMED -> AcceptResult.Accepted(invitationId)
            ConsumeOutcome.VETOED -> AcceptResult.Vetoed
            ConsumeOutcome.NOT_FOUND -> AcceptResult.NotFound
        }

    /** Idempotent [accept] by inviter/invited pair. See [acceptDetailed] and [accept]. */
    @JvmOverloads
    fun acceptDetailed(inviterId: UUID, invitedId: UUID, actor: ActorContext? = null): AcceptResult {
        val id = getInvite(inviterId, invitedId)?.id ?: return AcceptResult.NotFound
        return acceptDetailed(id, actor)
    }

    /** Idempotent [deny]: see [acceptDetailed]. */
    @JvmOverloads
    fun denyDetailed(invitationId: UUID, actor: ActorContext? = null): DenyResult =
        when (consume(invitationId, InvitationAction.DENIED, startsPairCooldown = true, actor = actor) { handler.onDeny(it) }) {
            ConsumeOutcome.CONSUMED -> DenyResult.Denied(invitationId)
            ConsumeOutcome.VETOED -> DenyResult.Vetoed
            ConsumeOutcome.NOT_FOUND -> DenyResult.NotFound
        }

    /** Idempotent [cancel]: see [acceptDetailed]. */
    @JvmOverloads
    fun cancelDetailed(invitationId: UUID, actor: ActorContext? = null): CancelResult =
        when (consume(invitationId, InvitationAction.CANCELLED, CancelReason.REVOKED, startsPairCooldown = true, actor = actor) {
            handler.onCancel(it, CancelReason.REVOKED)
        }) {
            ConsumeOutcome.CONSUMED -> CancelResult.Cancelled(invitationId)
            ConsumeOutcome.VETOED -> CancelResult.Vetoed
            ConsumeOutcome.NOT_FOUND -> CancelResult.NotFound
        }

    /**
     * Decline every pending invitation addressed to [invitedId], firing [InvitationHandler.onDeny] for
     * each. Returns how many were denied. Useful for "reject all" UX and on the invited player leaving.
     */
    fun denyAll(invitedId: UUID): Int =
        consumeAll(byInvited[invitedId].orEmpty(), InvitationAction.DENIED) { handler.onDeny(it) }

    /**
     * Revoke every pending invitation sent by [inviterId], firing [InvitationHandler.onCancel] with
     * [CancelReason.REVOKED] for each. Returns how many were cancelled. Unlike [clearFor] this only
     * touches invites where the player is the *inviter*, and reports [CancelReason.REVOKED] (a
     * deliberate revoke) rather than [CancelReason.PARTY_CLEARED].
     */
    fun cancelAllFrom(inviterId: UUID): Int =
        consumeAll(byInviter[inviterId].orEmpty(), InvitationAction.CANCELLED, CancelReason.REVOKED) {
            handler.onCancel(it, CancelReason.REVOKED)
        }

    /**
     * Drop every invitation that [playerId] is part of — as inviter *or* invited — firing
     * [InvitationHandler.onCancel] with [reason] for each. Use on disconnect (`PLAYER_QUIT`), island
     * disband (`PARTY_CLEARED`), or admin action (`ADMIN_CLEARED`). Returns how many were cleared.
     *
     * @param reason the cancel reason reported to the handler; defaults to [CancelReason.PARTY_CLEARED].
     */
    @JvmOverloads
    fun clearAllFor(playerId: UUID, reason: CancelReason = CancelReason.PARTY_CLEARED): Int =
        clearMatching(reason) { byInviter[playerId].orEmpty() + byInvited[playerId].orEmpty() }

    /** Drop every invitation where [playerId] is the *inviter*, firing [onCancel] with [reason]. */
    @JvmOverloads
    fun clearAsInviter(playerId: UUID, reason: CancelReason = CancelReason.PARTY_CLEARED): Int =
        clearMatching(reason) { byInviter[playerId].orEmpty() }

    /** Drop every invitation where [playerId] is the *invited* party, firing [onCancel] with [reason]. */
    @JvmOverloads
    fun clearAsInvited(playerId: UUID, reason: CancelReason = CancelReason.PARTY_CLEARED): Int =
        clearMatching(reason) { byInvited[playerId].orEmpty() }

    /**
     * Administrative override: cancel a specific invitation regardless of who the [admin] actor is, with
     * [CancelReason.ADMIN_CLEARED]. Unlike [cancelDetailed] this bypasses any registered [InvitationVeto]
     * (a moderator's clear shouldn't be blockable by a plugin's normal cancel event) while still firing
     * [InvitationHandler.onCancel] and notifying observers — so the audit sink records the override. The
     * [admin] context is accepted for symmetry and audit intent; pass [ActorContext.ADMIN] if you have
     * no specific moderator id.
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

    /**
     * Administrative override: drop every invitation [playerId] is part of (either direction) with
     * [CancelReason.ADMIN_CLEARED], for moderator "clear this player's invites" commands. Bypasses
     * vetoes; still fires hooks and audit. Returns the count cleared.
     */
    @JvmOverloads
    fun adminClearAllFor(playerId: UUID, admin: ActorContext = ActorContext.ADMIN): Int =
        clearMatching(CancelReason.ADMIN_CLEARED, actor = admin) { byInviter[playerId].orEmpty() + byInvited[playerId].orEmpty() }

    /**
     * Drop every invitation [playerId] is part of in either direction, firing
     * [InvitationHandler.onCancel] with [CancelReason.PARTY_CLEARED]. Retained for source
     * compatibility; new callers should prefer [clearAllFor] (which lets you pass a [CancelReason])
     * or the directional [clearAsInviter] / [clearAsInvited].
     */
    fun clearFor(playerId: UUID): Int = clearAllFor(playerId, CancelReason.PARTY_CLEARED)

    /** All invitations addressed to [invitedId], optionally sorted by [Invitation.createdAt]. */
    @JvmOverloads
    fun getInvitesFor(invitedId: UUID, sort: SortOrder? = null): List<T> =
        byInvited[invitedId].orEmpty().mapNotNull { byId[it] }.sortedBy(sort)

    /** All invitations sent by [inviterId], optionally sorted by [Invitation.createdAt]. */
    @JvmOverloads
    fun getInvitesFrom(inviterId: UUID, sort: SortOrder? = null): List<T> =
        byInviter[inviterId].orEmpty().mapNotNull { byId[it] }.sortedBy(sort)

    /**
     * Every pending invitation between [playerA] and [playerB], in *either* direction (A→B and B→A) —
     * for systems where the invite's direction doesn't matter to the caller. At most two for a given
     * pair under the default duplicate policy. Optionally sorted by [Invitation.createdAt].
     */
    @JvmOverloads
    fun getInvitesBetween(playerA: UUID, playerB: UUID, sort: SortOrder? = null): List<T> = synchronized(lock) {
        listOfNotNull(findPair(playerA, playerB), findPair(playerB, playerA))
    }.sortedBy(sort)

    /** Number of pending invitations addressed to [invitedId]. */
    fun countFor(invitedId: UUID): Int = byInvited[invitedId].orEmpty().size

    /** Number of pending invitations sent by [inviterId]. */
    fun countFrom(inviterId: UUID): Int = byInviter[inviterId].orEmpty().size

    /** Total pending invitations across all players. */
    fun pendingCount(): Int = byId.size

    /**
     * The most recently created invitation addressed to [invitedId], or `null` if there are none —
     * the common "accept the newest" / "respond to the latest" pattern. Ties on [Invitation.createdAt]
     * are broken arbitrarily.
     */
    fun getMostRecentFor(invitedId: UUID): T? =
        getInvitesFor(invitedId).maxByOrNull { it.createdAt }

    /** The invitation from one specific player to another, if any. */
    fun getInvite(inviterId: UUID, invitedId: UUID): T? = synchronized(lock) {
        findPair(inviterId, invitedId)
    }

    operator fun get(invitationId: UUID): T? = byId[invitationId]

    /** Every pending invitation, optionally sorted by [Invitation.createdAt]. */
    @JvmOverloads
    fun all(sort: SortOrder? = null): List<T> = byId.values.toList().sortedBy(sort)

    /**
     * Reload pending invitations from the [store] and re-arm their expiry. Call once on startup,
     * before any [send]. For each loaded invitation:
     *  - it is re-indexed (the in-memory indexes are rebuilt from the store), then
     *  - if its [Invitation.expiresAt] already passed while the server was offline it is expired
     *    immediately (removed from the store and [InvitationHandler.onExpire] fired), otherwise its
     *    expiry timer is re-scheduled for the remaining delay.
     *
     * [InvitationHandler.onSend] is **not** re-fired — these invitations were already announced in a
     * previous run. The loaded set is first reconciled per the configured [rehydratePolicy] (duplicate
     * ids, duplicate pairs, cap overflow), with any rejected rows optionally deleted from the store.
     * Returns the number of invitations rehydrated as still-pending (i.e. excluding any that were
     * dropped by policy or expired on load).
     */
    fun rehydrate(): Int {
        val loaded = guardStore("load") { store.load() }
        val (kept, dropped) = reconcileOnLoad(loaded)
        if (dropped.isNotEmpty() && rehydratePolicy.repairStore) {
            storeRemoveAll(dropped)
        }
        var stillPending = 0
        for (invitation in kept) {
            // Guard against a double-rehydrate or an id already live: an already-indexed invite must
            // not be re-indexed *or* have a second expiry timer armed (which would leak the first timer
            // and risk a double expiry). Skip the whole iteration, not just the index() call.
            val freshlyIndexed = synchronized(lock) {
                if (byId.containsKey(invitation.id)) false
                else { index(invitation); true }
            }
            if (!freshlyIndexed) continue
            if (scheduleExpiry(invitation)) stillPending++
        }
        return stillPending
    }

    /**
     * Apply [rehydratePolicy] to the raw [loaded] set: drop duplicate ids, duplicate (inviter,invited)
     * pairs (keeping the newest), and rows that exceed `maxPerInviter` / `maxPerInvited` (dropping the
     * oldest over the cap). Returns the rows to keep and the ids that were dropped. Pure / no mutation —
     * the caller indexes the kept rows and (optionally) deletes the dropped ones from the store.
     */
    private fun reconcileOnLoad(loaded: List<T>): Pair<List<T>, List<UUID>> {
        if (rehydratePolicy == RehydratePolicy.TRUST_STORE) return loaded to emptyList()
        val dropped = mutableListOf<UUID>()
        var rows = loaded

        if (rehydratePolicy.dropDuplicateIds) {
            val seen = HashSet<UUID>()
            rows = rows.filter { if (seen.add(it.id)) true else { dropped += it.id; false } }
        }
        if (rehydratePolicy.dropDuplicatePairs) {
            // Keep the newest per (inviter, invited); drop the rest.
            val byPair = HashMap<Pair<UUID, UUID>, T>()
            val keep = LinkedHashSet<UUID>()
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

    /** Drop the oldest rows that push any [keyOf] group over [cap]; record their ids in [dropped]. */
    private inline fun enforceCapOnLoad(rows: List<T>, cap: Int?, dropped: MutableList<UUID>, keyOf: (T) -> UUID): List<T> {
        if (cap == null) return rows
        val counts = HashMap<UUID, Int>()
        val keep = LinkedHashSet<UUID>()
        // Oldest first, so the survivors of an over-cap group are the newest.
        for (inv in rows.sortedByDescending { it.createdAt }) {
            val key = keyOf(inv)
            val n = counts.getOrDefault(key, 0)
            if (n < cap) { counts[key] = n + 1; keep += inv.id } else dropped += inv.id
        }
        return rows.filter { it.id in keep }
    }

    /**
     * Cancel every pending expiry timer so the manager can be dropped without leaking scheduled
     * tasks, then [close][InvitationStore.close] the store to release its resources (file handles,
     * JDBC connections, async queues — an [AsyncStore] flushes its backlog here). Does **not** fire
     * handler hooks or *delete* anything: the invitations remain pending and persisted, ready to be
     * picked up by [rehydrate] next startup. Call on plugin disable.
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

    // --- internals ---------------------------------------------------------------------------

    private fun applyDuplicateMutation(mutation: DuplicateMutation<T>, invitation: T, actor: ActorContext?) {
        cancelTimers(mutation.existing.id)
        // Atomic swap where the store supports it (the in-memory indexes were already swapped under lock).
        storeReplace(mutation.existing.id, invitation)
        if (mutation.fireCancel) {
            fireHook(mutation.existing, InvitationAction.CANCELLED, CancelReason.DUPLICATE_REPLACED, actor = actor) {
                handler.onCancel(it, CancelReason.DUPLICATE_REPLACED)
            }
        }
        // Announce the replacement as its own post-event regardless of whether onSend re-fires.
        notifyObservers(invitation, InvitationAction.REPLACED, replacedId = mutation.existing.id, actor = actor)
        if (scheduleExpiry(invitation) && mutation.fireSend) {
            fireHook(invitation, InvitationAction.SENT, actor = actor) { handler.onSend(it) }
        }
    }

    /** Distinguishes the three terminal outcomes of [consume] so callers can map them to typed results. */
    private enum class ConsumeOutcome { CONSUMED, NOT_FOUND, VETOED }

    /**
     * Remove + fire a terminal hook for [action]. Shared by accept/deny/cancel. Runs any
     * [InvitationVeto] *before* mutating state (so a cancelled Bukkit event aborts cleanly), then
     * unindexes under [lock], drops timers, persists, fires the handler hook and notifies observers.
     */
    private fun consume(
        invitationId: UUID,
        action: InvitationAction,
        cancelReason: CancelReason? = null,
        startsPairCooldown: Boolean,
        actor: ActorContext? = null,
        hook: (T) -> Unit,
    ): ConsumeOutcome {
        // Peek without mutating so a veto can abort without having to re-index.
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

    /**
     * Run a store *write* under the configured [storeFailurePolicy]. Used for all post-mutation writes
     * ([storeRemove], [storeRemoveAll], [storeReplace], and the [send] write under MUTATE_THEN_RETRY /
     * MARK_UNHEALTHY). Every failure is counted and logged; the policy then decides:
     *  - [StoreFailurePolicy.FAIL_BEFORE_MUTATING] — one attempt, rethrow on failure (the caller has
     *    already verified the store before mutating, so a failure here is exceptional).
     *  - [StoreFailurePolicy.MUTATE_THEN_RETRY] — up to [storeWriteRetries] extra attempts, then swallow
     *    (memory is the live truth; the write is best-effort).
     *  - [StoreFailurePolicy.MARK_UNHEALTHY] — as retry, but flip [healthy] false if all attempts fail.
     */
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
            StoreFailurePolicy.MUTATE_THEN_RETRY -> Unit // best-effort: swallow
            StoreFailurePolicy.MARK_UNHEALTHY -> {
                healthy = false
                logger.error("invitation manager marked unhealthy after store $op failed", failure)
            }
        }
    }

    private fun storeSave(invitation: T) = storeWrite("save") { store.save(invitation) }

    private fun storeRemove(id: UUID) = storeWrite("remove") { store.remove(id) }

    /** Batched removal — one store round-trip for all [ids] (see [InvitationStore.removeAll]). */
    private fun storeRemoveAll(ids: Collection<UUID>) {
        if (ids.isEmpty()) return
        storeWrite("removeAll") { store.removeAll(ids) }
    }

    /** Atomic swap (see [InvitationStore.replace]). */
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

    /**
     * Remove a batch of invitations under a single lock acquisition and fire [hook] for each. Shared
     * by [denyAll] / [cancelAllFrom]. [ids] is snapshotted before locking, so the live index may have
     * shrunk by the time we unindex — [unindex] returning null is tolerated. Returns the count removed.
     */
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

    /**
     * Drop every invitation whose id is produced by [select] (evaluated under the lock), firing
     * [InvitationHandler.onCancel] with [reason]. Shared by [clearAllFor] / [clearAsInviter] /
     * [clearAsInvited]. Returns the count cleared.
     */
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

    /** Cancel and drop the expiry timer and any expiry-warning timers for [invitationId]. */
    private fun cancelTimers(invitationId: UUID) {
        timers.remove(invitationId)?.cancel()
        warningTimers.remove(invitationId)?.forEach { it.cancel() }
    }

    /**
     * Schedule the expiry timer (and any [expiryWarningOffsetsMillis] warning timers). Returns false
     * if the invitation was already past its expiry and so was expired inline (caller should not also
     * fire onSend); true otherwise.
     *
     * Scheduler-race safety: the timer body does **not** rely on `timers[id]` having been published —
     * even a scheduler that runs [Scheduler.runLater] synchronously (firing the block before the
     * `timers[id] = …` assignment completes) is correct, because the body re-checks liveness by trying
     * to [unindex] under [lock] and bails if the invite is already gone. By that point [send] has
     * already [index]ed the invite (indexing precedes scheduling), so an immediate fire still finds it.
     */
    private fun scheduleExpiry(invitation: T): Boolean {
        val expiresAt = invitation.expiresAt ?: return true
        val delay = expiresAt - scheduler.now()
        if (delay <= 0) {
            // already expired — drop it inline rather than waiting a tick
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
            // re-check it still exists; accept/deny may have won the race
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

    /**
     * Arm one timer per configured [expiryWarningOffsetsMillis] entry that still falls strictly before
     * [expiresAt]. Each fires [InvitationHandler.onExpiryWarning] only if the invitation is still live,
     * with the (re-computed) remaining time to expiry.
     */
    private fun scheduleExpiryWarnings(invitation: T, expiresAt: Long) {
        if (expiryWarningOffsetsMillis.isEmpty()) return
        for (offset in expiryWarningOffsetsMillis) {
            if (offset <= 0) continue
            val warnAt = expiresAt - offset
            val delay = warnAt - scheduler.now()
            if (delay <= 0) continue // offset already in the past for this invite — skip
            val timer = scheduler.runLater(delay) {
                if (!byId.containsKey(invitation.id)) return@runLater
                val remaining = (expiresAt - scheduler.now()).coerceAtLeast(0)
                dispatch { guarded(invitation, InvitationAction.EXPIRED) { handler.onExpiryWarning(invitation, remaining) } }
            }
            warningTimers.getOrPut(invitation.id) { mutableListOf() }.add(timer)
        }
    }

    private fun dispatch(block: () -> Unit) = scheduler.runOnMainThread(block)

    /**
     * Consult every registered [InvitationVeto] for [action] on [invitation], *before* any state
     * mutation. Returns true if any veto blocks it. Runs inline on the calling thread (the caller must
     * be the main thread for the Bukkit event adapter to be correct) — never under [lock] and never
     * for [InvitationAction.SENT] (send-time vetoing is [handler.validate]). Veto exceptions are not
     * isolated: a throwing veto propagates, since swallowing it could let a blocked action through.
     */
    private fun isVetoed(invitation: T, action: InvitationAction, cancelReason: CancelReason? = null): Boolean {
        for (veto in vetoes) if (veto.isVetoed(invitation, action, cancelReason)) return true
        return false
    }

    /**
     * Fire the post-transition notification for a committed [action] on [invitation] to every
     * observer, on the main thread. Built once into a [LifecycleEvent] and shared. Each observer is
     * isolated per [errorPolicy] so one misbehaving sink can't break the others or the tick.
     */
    private fun notifyObservers(
        invitation: T,
        action: InvitationAction,
        cancelReason: CancelReason? = null,
        replacedId: UUID? = null,
        actor: ActorContext? = null,
    ) {
        if (observers.isEmpty()) return
        val event = LifecycleEvent(invitation, action, scheduler.now(), cancelReason, replacedId, actor)
        dispatch {
            for (observer in observers) {
                guarded(invitation, action) { observer.onEvent(event) }
            }
        }
    }

    /**
     * Run a handler hook or observer callback for [action], catching anything it throws and applying
     * [errorPolicy]: always log at ERROR and route to [errorCallback]; then swallow ([LifecycleErrorPolicy.ISOLATE])
     * or rethrow ([LifecycleErrorPolicy.PROPAGATE]). The [errorCallback] itself is guarded so a faulty
     * error sink can't mask the original failure.
     */
    private inline fun guarded(invitation: T, action: InvitationAction, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            logger.error("invitation lifecycle callback for $action threw on id=${invitation.id}", t)
            try {
                errorCallback.onLifecycleError(invitation, action, t)
            } catch (_: Throwable) {
                // a throwing error callback must not mask the original failure
            }
            if (errorPolicy == LifecycleErrorPolicy.PROPAGATE) throw t
        }
    }

    /** Dispatch a single handler hook on the main thread, isolated per [errorPolicy], then notify observers. */
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

    // index/unindex/findPair are only ever called while holding [lock]. The reverse-index values are
    // plain (non-concurrent) MutableSets: they are safe *only* because every add/remove/iterate of
    // them happens under [lock]. Lock-free read paths (getInvitesFor/From, findPair via get) snapshot
    // through ConcurrentHashMap.get and tolerate a set being mutated under them — they mapNotNull over
    // byId, so an id removed mid-iteration simply drops out. Do not mutate these sets off-lock.
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

    /** Apply a [SortOrder] over [Invitation.createdAt], or return the list unchanged when [sort] is null. */
    private fun List<T>.sortedBy(sort: SortOrder?): List<T> = when (sort) {
        null -> this
        SortOrder.NEWEST_FIRST -> sortedByDescending { it.createdAt }
        SortOrder.OLDEST_FIRST -> sortedBy { it.createdAt }
    }

    private fun findPair(inviterId: UUID, invitedId: UUID): T? =
        byInviter[inviterId].orEmpty().asSequence()
            .mapNotNull { byId[it] }
            .firstOrNull { it.invitedId == invitedId }

    private fun activeCooldownRemaining(inviterId: UUID, invitedId: UUID): Long? {
        val key = PairKey(inviterId, invitedId)
        val until = pairCooldownUntil[key] ?: return null
        val remaining = until - scheduler.now()
        if (remaining <= 0) {
            pairCooldownUntil.remove(key, until)
            return null
        }
        return remaining
    }

    private fun recordCooldown(inviterId: UUID, invitedId: UUID) {
        val millis = pairCooldownMillis?.takeIf { it > 0 } ?: return
        pairCooldownUntil[PairKey(inviterId, invitedId)] = scheduler.now() + millis
    }

    private data class PairKey(val inviterId: UUID, val invitedId: UUID)

    private data class DuplicateMutation<T : Invitation>(
        val existing: T,
        val result: SendResult,
        val fireCancel: Boolean,
        val fireSend: Boolean,
    )

    companion object {
        /**
         * Start configuring a manager fluently. Preferred over the constructor as the option set
         * grows (store, caps, …) and because named arguments don't carry across to Java callers.
         *
         * Kotlin:
         * ```
         * val m = InvitationManager.builder(handler, scheduler)
         *     .maxPerInviter(5)
         *     .maxPerInvited(10)
         *     .pairCooldownMillis(30_000)
         *     .duplicatePolicy(DuplicatePolicy.REPLACE_EXISTING)
         *     .selfInvitePolicy(SelfInvitePolicy.REJECT)
         *     .expiryWarningOffsetsMillis(10_000, 5_000)
         *     .build()
         * ```
         * Java:
         * ```
         * InvitationManager<MyInvite> m = InvitationManager.builder(handler, scheduler)
         *     .maxPerInviter(5)
         *     .maxPerInvited(10)
         *     .pairCooldownMillis(30000L)
         *     .duplicatePolicy(DuplicatePolicy.REPLACE_EXISTING)
         *     .expiryWarningOffsetsMillis(10000L, 5000L)
         *     .store(new JsonFileStore<>(...))
         *     .build();
         * ```
         */
        @JvmStatic
        fun <T : Invitation> builder(
            handler: InvitationHandler<T>,
            scheduler: Scheduler,
        ): Builder<T> = Builder(handler, scheduler)
    }

    /**
     * Fluent builder for [InvitationManager]. The two required collaborators ([handler], [scheduler])
     * are taken up front; everything else is optional and defaults to the constructor's defaults
     * (unlimited caps, volatile in-memory [store]). Each setter returns `this` for chaining.
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

        /** Cap on simultaneous pending invites from one inviter. Pass `null` (the default) for unlimited. */
        fun maxPerInviter(limit: Int?): Builder<T> = apply { this.maxPerInviter = limit }

        /** Cap on simultaneous pending invites addressed to one player. Pass `null` (the default) for unlimited. */
        fun maxPerInvited(limit: Int?): Builder<T> = apply { this.maxPerInvited = limit }

        /** Runtime-only per-pair cooldown in milliseconds. Pass `null` or `0` (the default) to disable. */
        fun pairCooldownMillis(millis: Long?): Builder<T> = apply { this.pairCooldownMillis = millis }

        /** How duplicate inviter/invited pairs are handled. Defaults to [DuplicatePolicy.REJECT_EXISTING]. */
        fun duplicatePolicy(policy: DuplicatePolicy): Builder<T> = apply { this.duplicatePolicy = policy }

        /** How self-invites (inviter == invited) are handled. Defaults to [SelfInvitePolicy.REJECT]. */
        fun selfInvitePolicy(policy: SelfInvitePolicy): Builder<T> = apply { this.selfInvitePolicy = policy }

        /**
         * Register a [ValidationPolicy] (abuse/permission/state guard) run before [send] indexes an
         * invite. May be called repeatedly; policies run in registration order and the first typed
         * [RejectionReason] wins, surfaced as [SendResult.PolicyRejected]. See the built-ins on
         * [ValidationPolicy] (self-invite, target online, ignoring, same party, party full, permissions,
         * world/server). Composes with the legacy [InvitationHandler.validate], which runs after them.
         */
        fun validationPolicy(policy: ValidationPolicy<T>): Builder<T> = apply { this.validationPolicies += policy }

        /**
         * Configure a send-volume [RateLimiter] (per inviter / per invited / per pair sliding windows),
         * surfaced as [SendResult.RateLimited]. Distinct from [pairCooldownMillis], which throttles
         * repeats after a terminal outcome. Runtime-only — not persisted, resets on restart.
         */
        fun rateLimiter(limiter: RateLimiter?): Builder<T> = apply { this.rateLimiter = limiter }

        /**
         * Convenience: build a [RateLimiter] from the given per-dimension limits (any may be null).
         * Uses the manager's [scheduler] as the clock.
         */
        fun rateLimits(
            perInviter: RateLimiter.Limit? = null,
            perInvited: RateLimiter.Limit? = null,
            perPair: RateLimiter.Limit? = null,
        ): Builder<T> = apply {
            this.rateLimiter = RateLimiter(perInviter, perInvited, perPair) { scheduler.now() }
        }

        /**
         * Offsets *before* expiry at which to fire [InvitationHandler.onExpiryWarning]. Empty (the
         * default) disables warnings. e.g. `expiryWarningOffsetsMillis(10_000, 5_000)` warns 10s and
         * 5s out. Non-positive offsets, and offsets larger than an invite's remaining time, are skipped.
         */
        fun expiryWarningOffsetsMillis(vararg offsetsMillis: Long): Builder<T> =
            apply { this.expiryWarningOffsetsMillis = offsetsMillis.toList() }

        /** Durability backend. Defaults to a volatile in-memory store (no cross-restart persistence). */
        fun store(store: InvitationStore<T>): Builder<T> = apply { this.store = store }

        /**
         * Register a passive post-transition [InvitationObserver]. May be called repeatedly to add
         * several; they fire in registration order, ahead of the built-in logging/metrics/audit
         * observers. On Bukkit, pass `EventFiringObserver()` to bridge onto the server event bus.
         */
        fun observer(observer: InvitationObserver<T>): Builder<T> = apply { this.observers += observer }

        /**
         * Register a pre-transition [InvitationVeto] for accept/deny/cancel/send. Any veto returning
         * true aborts the action with a typed `Vetoed` result. On Bukkit, pass `EventFiringVeto()` to
         * back the vetoes with cancellable events.
         */
        fun veto(veto: InvitationVeto<T>): Builder<T> = apply { this.vetoes += veto }

        /** Diagnostics sink (one line per transition + errors). Wired in as an observer. Defaults to no-op. */
        fun logger(logger: InvitationLogger): Builder<T> = apply { this.logger = logger }

        /** Counter sink for outcomes and store failures. Wired in as an observer. Defaults to no-op. */
        fun metrics(metrics: InvitationMetrics): Builder<T> = apply { this.metrics = metrics }

        /** Structured audit sink (one [AuditEntry] per transition). Wired in as an observer. Defaults to no-op. */
        fun audit(audit: InvitationAudit): Builder<T> = apply { this.audit = audit }

        /** What to do when a handler hook or observer throws. Defaults to [LifecycleErrorPolicy.ISOLATE]. */
        fun errorPolicy(policy: LifecycleErrorPolicy): Builder<T> = apply { this.errorPolicy = policy }

        /** Callback invoked (with context) whenever a handler hook or observer throws. Defaults to no-op. */
        fun errorCallback(callback: LifecycleErrorCallback<T>): Builder<T> = apply { this.errorCallback = callback }

        /** How to react when the [store] throws on a write. Defaults to [StoreFailurePolicy.FAIL_BEFORE_MUTATING]. */
        fun storeFailurePolicy(policy: StoreFailurePolicy): Builder<T> = apply { this.storeFailurePolicy = policy }

        /** Extra write attempts under the retry / unhealthy failure policies. Defaults to 2. */
        fun storeWriteRetries(retries: Int): Builder<T> = apply { this.storeWriteRetries = retries }

        /** How [rehydrate] reconciles duplicates and cap overflow on load. Defaults to [RehydratePolicy.REPAIR]. */
        fun rehydratePolicy(policy: RehydratePolicy): Builder<T> = apply { this.rehydratePolicy = policy }

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
            )
    }
}
