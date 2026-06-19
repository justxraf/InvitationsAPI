package com.justxraf.invitations
enum class StoreFailurePolicy {
    FAIL_BEFORE_MUTATING,
    MUTATE_THEN_RETRY,
    MARK_UNHEALTHY,
}
