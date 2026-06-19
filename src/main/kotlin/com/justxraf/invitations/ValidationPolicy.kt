package com.justxraf.invitations

import java.util.UUID
/**
 * Composable, actor-aware pre-send validation rule that returns a structured [RejectionReason] (or
 * `null` to allow the send). The companion provides ready-made rules for the common abuse-protection
 * cases; combine them with [andThen] or [all]. Rules run before the manager lock and may consult
 * external state (presence, ignore lists, party membership) through the supplied SPI seams.
 */
fun interface ValidationPolicy<T : Invitation> {
    /**
     * @param existing invitations already visible for the pair, as a snapshot.
     * @param actor authorization context, or `null` when none was supplied.
     * @return a [RejectionReason] to reject, or `null` to allow.
     */
    fun validate(invitation: T, existing: List<T>, actor: ActorContext?): RejectionReason?

    /** Chain another rule: this rule wins if it rejects, otherwise [other] is consulted. */
    fun andThen(other: ValidationPolicy<T>): ValidationPolicy<T> =
        ValidationPolicy { invitation, existing, actor ->
            validate(invitation, existing, actor) ?: other.validate(invitation, existing, actor)
        }

    companion object {
        /** Combine many rules; the first to reject wins, short-circuiting the rest. */
        @JvmStatic
        fun <T : Invitation> all(policies: List<ValidationPolicy<T>>): ValidationPolicy<T> =
            ValidationPolicy { invitation, existing, actor ->
                for (p in policies) p.validate(invitation, existing, actor)?.let { return@ValidationPolicy it }
                null
            }

        /** Reject when `inviterId == invitedId`. */
        @JvmStatic
        fun <T : Invitation> selfInvite(): ValidationPolicy<T> =
            ValidationPolicy { inv, _, _ ->
                if (inv.inviterId == inv.invitedId)
                    RejectionReason(RejectionReason.Code.SELF_INVITE, "invitation.reject.self_invite", "You cannot invite yourself.")
                else null
            }
        /** Reject when the invited player is offline, unless an admin actor bypasses it. */
        @JvmStatic
        @JvmOverloads
        fun <T : Invitation> targetOnline(allowAdminBypass: Boolean = true, isOnline: Presence): ValidationPolicy<T> =
            ValidationPolicy { inv, _, actor ->
                if (allowAdminBypass && actor?.admin == true) return@ValidationPolicy null
                if (isOnline.isOnline(inv.invitedId)) null
                else RejectionReason(RejectionReason.Code.TARGET_OFFLINE, "invitation.reject.target_offline", "That player is offline.")
            }
        /** Reject when the invited player is ignoring the inviter. */
        @JvmStatic
        fun <T : Invitation> targetNotIgnoring(ignores: IgnoreList): ValidationPolicy<T> =
            ValidationPolicy { inv, _, _ ->
                if (ignores.ignores(inv.invitedId, inv.inviterId))
                    RejectionReason(RejectionReason.Code.TARGET_IGNORING_INVITER, "invitation.reject.ignored", "That player is not accepting invites from you.")
                else null
            }
        /** Reject when both players are already in the same party/group. */
        @JvmStatic
        fun <T : Invitation> notAlreadyInSameParty(membership: PartyMembership): ValidationPolicy<T> =
            ValidationPolicy { inv, _, _ ->
                if (membership.sameParty(inv.inviterId, inv.invitedId))
                    RejectionReason(RejectionReason.Code.ALREADY_IN_SAME_PARTY, "invitation.reject.same_party", "That player is already in your party.")
                else null
            }
        /** Reject when the inviter's party is at capacity; the message includes `{current}`/`{max}`. */
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
        /** Reject unless the actor holds [node] (or is admin, when [allowAdminBypass] is set). */
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
        /** Reject unless the invited player holds [node] per the supplied checker. */
        @JvmStatic
        fun <T : Invitation> invitedHasPermission(node: String, permissions: ActorContext.PermissionChecker): ValidationPolicy<T> =
            ValidationPolicy { inv, _, _ ->
                if (permissions.hasPermission(inv.invitedId, node)) null
                else RejectionReason(
                    RejectionReason.Code.INVITED_LACKS_PERMISSION, "invitation.reject.invited_no_permission",
                    "That player cannot be invited.", mapOf("node" to node),
                )
            }
        /**
         * Reject when the actor's world/server is outside the allow-lists. A `null` allow-list means
         * "no restriction" for that dimension; admins bypass when [allowAdminBypass] is set.
         */
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
    /** Host-platform seam: is a player currently online? Used by [targetOnline]. */
    fun interface Presence {
        fun isOnline(playerId: UUID): Boolean
    }

    /** Host-platform seam: does [playerId] ignore [otherId]? Used by [targetNotIgnoring]. */
    fun interface IgnoreList {
        fun ignores(playerId: UUID, otherId: UUID): Boolean
    }

    /** Host-platform seam: are two players in the same party? Used by [notAlreadyInSameParty]. */
    fun interface PartyMembership {
        fun sameParty(a: UUID, b: UUID): Boolean
    }

    /** Host-platform seam: current/max size of a player's party. Used by [partyNotFull]. */
    fun interface PartyCapacity {
        /** @return the party's capacity, or `null` if the player has no party (no restriction). */
        fun capacityFor(playerId: UUID): Capacity?

        /** A party's current member count and maximum. */
        data class Capacity(val current: Int, val max: Int)
    }
}
