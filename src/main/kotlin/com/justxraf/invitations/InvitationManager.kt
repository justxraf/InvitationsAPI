package com.justxraf.invitations

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
fun isHealthy(): Boolean = healthy
private val observers: List<InvitationObserver<T>> = buildList {
        addAll(observers)
        if (logger !== InvitationLogger.Noop) add(LoggingObserver(logger))
        if (metrics !== InvitationMetrics.Noop) add(MetricsObserver(metrics))
        if (audit !== InvitationAudit.Noop) add(AuditObserver(audit))
    }
sealed interface SendResult {
        data class Accepted(val invitationId: UUID) : SendResult
data class Duplicate(val existingId: UUID) : SendResult
data class Replaced(val replacedId: UUID, val invitationId: UUID) : SendResult
data class Refreshed(val invitationId: UUID) : SendResult
data class Rejected(val reason: String) : SendResult
data class PolicyRejected(val reason: RejectionReason) : SendResult
data class RateLimited(val retryAfterMillis: Long) : SendResult
data class LimitReached(val limit: Int) : SendResult
data class CooldownActive(val remainingMillis: Long) : SendResult
data object SelfInvite : SendResult
data object Vetoed : SendResult
data class StoreFailure(val cause: Throwable) : SendResult
    }
class StoreWriteFailedException(message: String, cause: Throwable) : RuntimeException(message, cause)
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
@JvmOverloads
    fun accept(invitationId: UUID, actor: ActorContext? = null): Boolean =
        acceptDetailed(invitationId, actor) is AcceptResult.Accepted
@JvmOverloads
    fun accept(inviterId: UUID, invitedId: UUID, actor: ActorContext? = null): Boolean =
        acceptDetailed(inviterId, invitedId, actor) is AcceptResult.Accepted
@JvmOverloads
    fun deny(invitationId: UUID, actor: ActorContext? = null): Boolean =
        denyDetailed(invitationId, actor) is DenyResult.Denied
@JvmOverloads
    fun cancel(invitationId: UUID, actor: ActorContext? = null): Boolean =
        cancelDetailed(invitationId, actor) is CancelResult.Cancelled
@JvmOverloads
    fun acceptDetailed(invitationId: UUID, actor: ActorContext? = null): AcceptResult =
        when (consume(invitationId, InvitationAction.ACCEPTED, startsPairCooldown = false, actor = actor) { handler.onAccept(it) }) {
            ConsumeOutcome.CONSUMED -> AcceptResult.Accepted(invitationId)
            ConsumeOutcome.VETOED -> AcceptResult.Vetoed
            ConsumeOutcome.NOT_FOUND -> AcceptResult.NotFound
        }
@JvmOverloads
    fun acceptDetailed(inviterId: UUID, invitedId: UUID, actor: ActorContext? = null): AcceptResult {
        val id = getInvite(inviterId, invitedId)?.id ?: return AcceptResult.NotFound
        return acceptDetailed(id, actor)
    }
@JvmOverloads
    fun denyDetailed(invitationId: UUID, actor: ActorContext? = null): DenyResult =
        when (consume(invitationId, InvitationAction.DENIED, startsPairCooldown = true, actor = actor) { handler.onDeny(it) }) {
            ConsumeOutcome.CONSUMED -> DenyResult.Denied(invitationId)
            ConsumeOutcome.VETOED -> DenyResult.Vetoed
            ConsumeOutcome.NOT_FOUND -> DenyResult.NotFound
        }
@JvmOverloads
    fun cancelDetailed(invitationId: UUID, actor: ActorContext? = null): CancelResult =
        when (consume(invitationId, InvitationAction.CANCELLED, CancelReason.REVOKED, startsPairCooldown = true, actor = actor) {
            handler.onCancel(it, CancelReason.REVOKED)
        }) {
            ConsumeOutcome.CONSUMED -> CancelResult.Cancelled(invitationId)
            ConsumeOutcome.VETOED -> CancelResult.Vetoed
            ConsumeOutcome.NOT_FOUND -> CancelResult.NotFound
        }
fun denyAll(invitedId: UUID): Int =
        consumeAll(byInvited[invitedId].orEmpty(), InvitationAction.DENIED) { handler.onDeny(it) }
fun cancelAllFrom(inviterId: UUID): Int =
        consumeAll(byInviter[inviterId].orEmpty(), InvitationAction.CANCELLED, CancelReason.REVOKED) {
            handler.onCancel(it, CancelReason.REVOKED)
        }
@JvmOverloads
    fun clearAllFor(playerId: UUID, reason: CancelReason = CancelReason.PARTY_CLEARED): Int =
        clearMatching(reason) { byInviter[playerId].orEmpty() + byInvited[playerId].orEmpty() }
@JvmOverloads
    fun clearAsInviter(playerId: UUID, reason: CancelReason = CancelReason.PARTY_CLEARED): Int =
        clearMatching(reason) { byInviter[playerId].orEmpty() }
@JvmOverloads
    fun clearAsInvited(playerId: UUID, reason: CancelReason = CancelReason.PARTY_CLEARED): Int =
        clearMatching(reason) { byInvited[playerId].orEmpty() }
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
@JvmOverloads
    fun adminClearAllFor(playerId: UUID, admin: ActorContext = ActorContext.ADMIN): Int =
        clearMatching(CancelReason.ADMIN_CLEARED, actor = admin) { byInviter[playerId].orEmpty() + byInvited[playerId].orEmpty() }
fun clearFor(playerId: UUID): Int = clearAllFor(playerId, CancelReason.PARTY_CLEARED)
@JvmOverloads
    fun getInvitesFor(invitedId: UUID, sort: SortOrder? = null): List<T> =
        byInvited[invitedId].orEmpty().mapNotNull { byId[it] }.sortedBy(sort)
@JvmOverloads
    fun getInvitesFrom(inviterId: UUID, sort: SortOrder? = null): List<T> =
        byInviter[inviterId].orEmpty().mapNotNull { byId[it] }.sortedBy(sort)
@JvmOverloads
    fun getInvitesBetween(playerA: UUID, playerB: UUID, sort: SortOrder? = null): List<T> = synchronized(lock) {
        listOfNotNull(findPair(playerA, playerB), findPair(playerB, playerA))
    }.sortedBy(sort)
fun countFor(invitedId: UUID): Int = byInvited[invitedId].orEmpty().size
fun countFrom(inviterId: UUID): Int = byInviter[inviterId].orEmpty().size
fun pendingCount(): Int = byId.size
fun getMostRecentFor(invitedId: UUID): T? =
        getInvitesFor(invitedId).maxByOrNull { it.createdAt }
fun getInvite(inviterId: UUID, invitedId: UUID): T? = synchronized(lock) {
        findPair(inviterId, invitedId)
    }

    operator fun get(invitationId: UUID): T? = byId[invitationId]
@JvmOverloads
    fun all(sort: SortOrder? = null): List<T> = byId.values.toList().sortedBy(sort)
fun rehydrate(): Int {
        val loaded = guardStore("load") { store.load() }
        val (kept, dropped) = reconcileOnLoad(loaded)
        if (dropped.isNotEmpty() && rehydratePolicy.repairStore) {
            storeRemoveAll(dropped)
        }
        var stillPending = 0
        for (invitation in kept) {
            val freshlyIndexed = synchronized(lock) {
                if (byId.containsKey(invitation.id)) false
                else { index(invitation); true }
            }
            if (!freshlyIndexed) continue
            if (scheduleExpiry(invitation)) stillPending++
        }
        return stillPending
    }
private fun reconcileOnLoad(loaded: List<T>): Pair<List<T>, List<UUID>> {
        if (rehydratePolicy == RehydratePolicy.TRUST_STORE) return loaded to emptyList()
        val dropped = mutableListOf<UUID>()
        var rows = loaded

        if (rehydratePolicy.dropDuplicateIds) {
            val seen = HashSet<UUID>()
            rows = rows.filter { if (seen.add(it.id)) true else { dropped += it.id; false } }
        }
        if (rehydratePolicy.dropDuplicatePairs) {
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
private inline fun enforceCapOnLoad(rows: List<T>, cap: Int?, dropped: MutableList<UUID>, keyOf: (T) -> UUID): List<T> {
        if (cap == null) return rows
        val counts = HashMap<UUID, Int>()
        val keep = LinkedHashSet<UUID>()
        for (inv in rows.sortedByDescending { it.createdAt }) {
            val key = keyOf(inv)
            val n = counts.getOrDefault(key, 0)
            if (n < cap) { counts[key] = n + 1; keep += inv.id } else dropped += inv.id
        }
        return rows.filter { it.id in keep }
    }
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
        val event = LifecycleEvent(invitation, action, scheduler.now(), cancelReason, replacedId, actor)
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
@JvmStatic
        fun <T : Invitation> builder(
            handler: InvitationHandler<T>,
            scheduler: Scheduler,
        ): Builder<T> = Builder(handler, scheduler)
    }
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
fun maxPerInviter(limit: Int?): Builder<T> = apply { this.maxPerInviter = limit }
fun maxPerInvited(limit: Int?): Builder<T> = apply { this.maxPerInvited = limit }
fun pairCooldownMillis(millis: Long?): Builder<T> = apply { this.pairCooldownMillis = millis }
fun duplicatePolicy(policy: DuplicatePolicy): Builder<T> = apply { this.duplicatePolicy = policy }
fun selfInvitePolicy(policy: SelfInvitePolicy): Builder<T> = apply { this.selfInvitePolicy = policy }
fun validationPolicy(policy: ValidationPolicy<T>): Builder<T> = apply { this.validationPolicies += policy }
fun rateLimiter(limiter: RateLimiter?): Builder<T> = apply { this.rateLimiter = limiter }
fun rateLimits(
            perInviter: RateLimiter.Limit? = null,
            perInvited: RateLimiter.Limit? = null,
            perPair: RateLimiter.Limit? = null,
        ): Builder<T> = apply {
            this.rateLimiter = RateLimiter(perInviter, perInvited, perPair) { scheduler.now() }
        }
fun expiryWarningOffsetsMillis(vararg offsetsMillis: Long): Builder<T> =
            apply { this.expiryWarningOffsetsMillis = offsetsMillis.toList() }
fun store(store: InvitationStore<T>): Builder<T> = apply { this.store = store }
fun observer(observer: InvitationObserver<T>): Builder<T> = apply { this.observers += observer }
fun veto(veto: InvitationVeto<T>): Builder<T> = apply { this.vetoes += veto }
fun logger(logger: InvitationLogger): Builder<T> = apply { this.logger = logger }
fun metrics(metrics: InvitationMetrics): Builder<T> = apply { this.metrics = metrics }
fun audit(audit: InvitationAudit): Builder<T> = apply { this.audit = audit }
fun errorPolicy(policy: LifecycleErrorPolicy): Builder<T> = apply { this.errorPolicy = policy }
fun errorCallback(callback: LifecycleErrorCallback<T>): Builder<T> = apply { this.errorCallback = callback }
fun storeFailurePolicy(policy: StoreFailurePolicy): Builder<T> = apply { this.storeFailurePolicy = policy }
fun storeWriteRetries(retries: Int): Builder<T> = apply { this.storeWriteRetries = retries }
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
