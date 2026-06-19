package com.justxraf.invitations

import java.util.UUID
class ActorContext(
    val actorId: UUID? = null,
    val permissions: PermissionChecker = PermissionChecker.NONE,
    val worldId: UUID? = null,
    val serverId: String? = null,
    val admin: Boolean = false,
) {
fun has(node: String): Boolean = actorId != null && permissions.hasPermission(actorId, node)
fun hasOrAdmin(node: String): Boolean = admin || has(node)
fun interface PermissionChecker {
        fun hasPermission(playerId: UUID, node: String): Boolean

        companion object {
@JvmField
            val NONE: PermissionChecker = PermissionChecker { _, _ -> false }
        }
    }

    companion object {
@JvmField
        val ADMIN: ActorContext = ActorContext(admin = true)
    }
}
