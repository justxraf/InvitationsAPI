package com.justxraf.networkapi.invitations

import java.util.UUID

/**
 * Optional actor/authorization context threaded through [InvitationManager.send] and the terminal
 * operations so plugins that need actor-aware authorization can answer "*who* is doing this, where,
 * and with what permissions" without the core depending on Bukkit.
 *
 * The core never interprets these fields; it only passes the context to [ValidationPolicy] checks
 * (and records [admin] on the audit trail). A null context — the default on every operation — means
 * "no actor info supplied"; policies that need it should then treat the action as coming from the
 * invite's own inviter/invited and with no extra permissions.
 *
 * @param actorId the player actually performing the action (usually the inviter on send, the invited
 * player on accept/deny, the inviter on cancel), or null if not a player (console/automation).
 * @param permissions a permission-check seam: returns whether [actorId] holds a node. Defaults to
 * granting nothing. On Bukkit, back this with `player::hasPermission`.
 * @param worldId the world the action happens in, for [ValidationPolicy.WorldOrServerRestriction];
 * null when not applicable.
 * @param serverId the (proxy/network) server id the action happens on; null when not applicable.
 * @param admin true when this is a privileged override (see [InvitationManager.adminClear] and the
 * admin-aware send): policies that consult [admin] may bypass restrictions, and the action is still
 * fully recorded on the audit sink so overrides remain traceable.
 */
class ActorContext(
    val actorId: UUID? = null,
    val permissions: PermissionChecker = PermissionChecker.NONE,
    val worldId: UUID? = null,
    val serverId: String? = null,
    val admin: Boolean = false,
) {
    /** True iff [actorId] holds [node] per [permissions]. */
    fun has(node: String): Boolean = actorId != null && permissions.hasPermission(actorId, node)

    /** Whether the actor holds [node], or — for an [admin] context — always true. */
    fun hasOrAdmin(node: String): Boolean = admin || has(node)

    /** A permission lookup seam, kept Bukkit-free so the core can stay server-agnostic. */
    fun interface PermissionChecker {
        fun hasPermission(playerId: UUID, node: String): Boolean

        companion object {
            /** Grants no permissions — the safe default when no checker is supplied. */
            @JvmField
            val NONE: PermissionChecker = PermissionChecker { _, _ -> false }
        }
    }

    companion object {
        /** A bare administrative override context with no specific actor — bypasses policy restrictions. */
        @JvmField
        val ADMIN: ActorContext = ActorContext(admin = true)
    }
}
