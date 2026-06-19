package com.justxraf.networkapi.invitations

/**
 * How an [InvitationManager] reacts when its [InvitationStore] throws during a write
 * ([InvitationStore.save], [remove][InvitationStore.remove], [removeAll][InvitationStore.removeAll],
 * or [replace][InvitationStore.replace]).
 *
 * The choice trades memory/store consistency against availability:
 *
 * - [FAIL_BEFORE_MUTATING] — write to the store *first*; only mutate the in-memory indexes if the
 *   store accepted the write. A store failure aborts the operation and leaves memory untouched, so
 *   memory never holds an invitation the store doesn't. This is the safest default: the store is the
 *   source of truth and the two never diverge. The cost is that a flaky store can block sends.
 *
 * - [MUTATE_THEN_RETRY] — mutate memory first for responsiveness, then write through; on failure the
 *   write is retried a bounded number of times before being reported. Memory may briefly hold an
 *   invitation the store hasn't durably recorded (lost on a crash before a retry succeeds), but the
 *   server thread isn't blocked on a slow store. Best paired with [AsyncStore].
 *
 * - [MARK_UNHEALTHY] — like [MUTATE_THEN_RETRY] for the in-flight operation, but a persistent failure
 *   flips the manager into an unhealthy state ([InvitationManager.isHealthy]) so callers/health checks
 *   can react (e.g. stop accepting new invites) rather than silently drifting from the store.
 */
enum class StoreFailurePolicy {
    FAIL_BEFORE_MUTATING,
    MUTATE_THEN_RETRY,
    MARK_UNHEALTHY,
}
