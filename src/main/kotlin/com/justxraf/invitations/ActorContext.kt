package com.justxraf.invitations

import java.util.UUID
/**
 * Optional authorization context describing *who* triggered an action and from where. Passed to
 * actor-aware operations and carried onto [LifecycleEvent]/[AuditEntry] for permission checks and
 * audit. All fields are optional so callers supply only what they have.
 *
 * @property actorId the acting player, or `null` for system actions.
 * @property permissions checker used by [has]/[hasOrAdmin].
 * @property worldId world the action originated in, if relevant.
 * @property serverId server/proxy node the action originated on, if relevant.
 * @property admin whether this actor bypasses permission checks (see [ADMIN]).
 */
class ActorContext(
    val actorId: UUID? = null,
    val permissions: PermissionChecker = PermissionChecker.NONE,
    val worldId: UUID? = null,
    val serverId: String? = null,
    val admin: Boolean = false,
) {
    /** `true` if the actor exists and holds the permission [node]. */
    fun has(node: String): Boolean = actorId != null && permissions.hasPermission(actorId, node)

    /** `true` if the actor is an [admin] or holds the permission [node]. */
    fun hasOrAdmin(node: String): Boolean = admin || has(node)

    /** Bridges to the host platform's permission system. */
    fun interface PermissionChecker {
        /** @return `true` if [playerId] holds permission [node]. */
        fun hasPermission(playerId: UUID, node: String): Boolean

        companion object {
            /** Checker that grants nothing. The default when no platform checker is supplied. */
            @JvmField
            val NONE: PermissionChecker = PermissionChecker { _, _ -> false }
        }
    }

    companion object {
        /** A context that bypasses all permission checks, for trusted/admin code paths. */
        @JvmField
        val ADMIN: ActorContext = ActorContext(admin = true)
    }
}
