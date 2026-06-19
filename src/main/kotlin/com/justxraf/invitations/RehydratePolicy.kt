package com.justxraf.invitations
data class RehydratePolicy(
    val dropDuplicateIds: Boolean,
    val dropDuplicatePairs: Boolean,
    val enforceCaps: Boolean,
    val repairStore: Boolean,
) {
    companion object {
@JvmField
        val TRUST_STORE = RehydratePolicy(
            dropDuplicateIds = false,
            dropDuplicatePairs = false,
            enforceCaps = false,
            repairStore = false,
        )
@JvmField
        val REPAIR = RehydratePolicy(
            dropDuplicateIds = true,
            dropDuplicatePairs = true,
            enforceCaps = true,
            repairStore = true,
        )
@JvmField
        val DROP_INVALID = RehydratePolicy(
            dropDuplicateIds = true,
            dropDuplicatePairs = true,
            enforceCaps = true,
            repairStore = false,
        )
    }
}
