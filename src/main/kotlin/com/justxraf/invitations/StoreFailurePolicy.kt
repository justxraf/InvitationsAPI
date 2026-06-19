package com.justxraf.invitations
/**
 * How [InvitationManager] reacts when the backing [InvitationStore] throws while persisting a
 * lifecycle change.
 */
enum class StoreFailurePolicy {
    /**
     * Treat the store as the source of truth: attempt the write first and roll the in-memory
     * indexes back if it fails, returning `SendResult.StoreFailure`. Memory never drifts from disk.
     */
    FAIL_BEFORE_MUTATING,

    /** Apply the change to memory immediately and retry persistence; favours availability over durability. */
    MUTATE_THEN_RETRY,

    /** Mark the manager unhealthy (see [InvitationManager.isHealthy]) on the first store failure. */
    MARK_UNHEALTHY,
}
