package com.justxraf.invitations

import java.util.UUID
fun interface ValidationPolicy<T : Invitation> {
fun validate(invitation: T, existing: List<T>, actor: ActorContext?): RejectionReason?
fun andThen(other: ValidationPolicy<T>): ValidationPolicy<T> =
        ValidationPolicy { invitation, existing, actor ->
            validate(invitation, existing, actor) ?: other.validate(invitation, existing, actor)
        }

    companion object {
@JvmStatic
        fun <T : Invitation> all(policies: List<ValidationPolicy<T>>): ValidationPolicy<T> =
            ValidationPolicy { invitation, existing, actor ->
                for (p in policies) p.validate(invitation, existing, actor)?.let { return@ValidationPolicy it }
                null
            }
@JvmStatic
        fun <T : Invitation> selfInvite(): ValidationPolicy<T> =
            ValidationPolicy { inv, _, _ ->
                if (inv.inviterId == inv.invitedId)
                    RejectionReason(RejectionReason.Code.SELF_INVITE, "invitation.reject.self_invite", "You cannot invite yourself.")
                else null
            }
@JvmStatic
        @JvmOverloads
        fun <T : Invitation> targetOnline(allowAdminBypass: Boolean = true, isOnline: Presence): ValidationPolicy<T> =
            ValidationPolicy { inv, _, actor ->
                if (allowAdminBypass && actor?.admin == true) return@ValidationPolicy null
                if (isOnline.isOnline(inv.invitedId)) null
                else RejectionReason(RejectionReason.Code.TARGET_OFFLINE, "invitation.reject.target_offline", "That player is offline.")
            }
@JvmStatic
        fun <T : Invitation> targetNotIgnoring(ignores: IgnoreList): ValidationPolicy<T> =
            ValidationPolicy { inv, _, _ ->
                if (ignores.ignores(inv.invitedId, inv.inviterId))
                    RejectionReason(RejectionReason.Code.TARGET_IGNORING_INVITER, "invitation.reject.ignored", "That player is not accepting invites from you.")
                else null
            }
@JvmStatic
        fun <T : Invitation> notAlreadyInSameParty(membership: PartyMembership): ValidationPolicy<T> =
            ValidationPolicy { inv, _, _ ->
                if (membership.sameParty(inv.inviterId, inv.invitedId))
                    RejectionReason(RejectionReason.Code.ALREADY_IN_SAME_PARTY, "invitation.reject.same_party", "That player is already in your party.")
                else null
            }
@JvmStatic
        fun <T : Invitation> partyNotFull(capacity: PartyCapacity): ValidationPolicy<T> =
            ValidationPolicy { inv, _, _ ->
                val cap = capacity.capacityFor(inv.inviterId) ?: return@ValidationPolicy null
                if (cap.current >= cap.max)
                    RejectionReason(
                        RejectionReason.Code.PARTY_FULL, "invitation.reject.party_full",
                        "Your party is full ({current}/{max}).",
                        mapOf("current" to cap.current.toString(), "max" to cap.max.toString()),
                    )
                else null
            }
@JvmStatic
        @JvmOverloads
        fun <T : Invitation> inviterHasPermission(node: String, allowAdminBypass: Boolean = true): ValidationPolicy<T> =
            ValidationPolicy { _, _, actor ->
                val ok = if (allowAdminBypass) (actor?.hasOrAdmin(node) ?: false) else (actor?.has(node) ?: false)
                if (ok) null
                else RejectionReason(
                    RejectionReason.Code.INVITER_LACKS_PERMISSION, "invitation.reject.inviter_no_permission",
                    "You don't have permission to invite players.", mapOf("node" to node),
                )
            }
@JvmStatic
        fun <T : Invitation> invitedHasPermission(node: String, permissions: ActorContext.PermissionChecker): ValidationPolicy<T> =
            ValidationPolicy { inv, _, _ ->
                if (permissions.hasPermission(inv.invitedId, node)) null
                else RejectionReason(
                    RejectionReason.Code.INVITED_LACKS_PERMISSION, "invitation.reject.invited_no_permission",
                    "That player cannot be invited.", mapOf("node" to node),
                )
            }
@JvmStatic
        @JvmOverloads
        fun <T : Invitation> worldOrServerRestriction(
            allowedWorlds: Set<UUID>? = null,
            allowedServers: Set<String>? = null,
            allowAdminBypass: Boolean = true,
        ): ValidationPolicy<T> =
            ValidationPolicy { _, _, actor ->
                if (allowAdminBypass && actor?.admin == true) return@ValidationPolicy null
                val world = actor?.worldId
                if (allowedWorlds != null && world != null && world !in allowedWorlds)
                    return@ValidationPolicy RejectionReason(
                        RejectionReason.Code.WORLD_OR_SERVER_RESTRICTED, "invitation.reject.world_restricted",
                        "Invites are disabled here.",
                    )
                val server = actor?.serverId
                if (allowedServers != null && server != null && server !in allowedServers)
                    return@ValidationPolicy RejectionReason(
                        RejectionReason.Code.WORLD_OR_SERVER_RESTRICTED, "invitation.reject.server_restricted",
                        "Invites are disabled on this server.",
                    )
                null
            }
    }
fun interface Presence {
        fun isOnline(playerId: UUID): Boolean
    }
fun interface IgnoreList {
        fun ignores(playerId: UUID, otherId: UUID): Boolean
    }
fun interface PartyMembership {
        fun sameParty(a: UUID, b: UUID): Boolean
    }
fun interface PartyCapacity {
        fun capacityFor(playerId: UUID): Capacity?

        data class Capacity(val current: Int, val max: Int)
    }
}
