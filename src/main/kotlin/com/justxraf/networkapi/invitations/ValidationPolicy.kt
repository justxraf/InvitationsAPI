package com.justxraf.networkapi.invitations

import java.util.UUID

/**
 * A composable, optional guard run before [InvitationManager.send] registers an invitation, emitting a
 * typed [RejectionReason] (never free-form prose) when it blocks. Several policies can be registered;
 * they run in order and the first non-null rejection wins. Policies run **outside** the manager lock
 * (like [InvitationHandler.validate]) so they may do blocking lookups — name resolution, party state,
 * permission checks — without stalling other threads; they are therefore advisory, with the manager's
 * in-lock duplicate/cap/cooldown checks remaining authoritative (see [InvitationManager.send]).
 *
 * This is §6's "built-in optional validation policies for common cases". Each built-in delegates the
 * environment-specific question (is the target online? are they in my party? is the party full?) to a
 * small Bukkit-free seam so the policy logic itself stays server-agnostic and unit-testable.
 */
fun interface ValidationPolicy<T : Invitation> {
    /**
     * Inspect [invitation] (with the inviter's [existing] invites and the optional [actor] context)
     * and return a [RejectionReason] to block it, or null to allow. Must not mutate manager state.
     */
    fun validate(invitation: T, existing: List<T>, actor: ActorContext?): RejectionReason?

    /** Run [other] only if `this` allowed the invite — short-circuit composition. */
    fun andThen(other: ValidationPolicy<T>): ValidationPolicy<T> =
        ValidationPolicy { invitation, existing, actor ->
            validate(invitation, existing, actor) ?: other.validate(invitation, existing, actor)
        }

    companion object {
        /** Run every policy in order; the first rejection wins. Empty list ⇒ always allow. */
        @JvmStatic
        fun <T : Invitation> all(policies: List<ValidationPolicy<T>>): ValidationPolicy<T> =
            ValidationPolicy { invitation, existing, actor ->
                for (p in policies) p.validate(invitation, existing, actor)?.let { return@ValidationPolicy it }
                null
            }

        /**
         * Reject self-invites (inviter == invited). Redundant with [SelfInvitePolicy.REJECT] but
         * provided here so a single policy chain can express the whole rule set; emits a typed reason.
         */
        @JvmStatic
        fun <T : Invitation> selfInvite(): ValidationPolicy<T> =
            ValidationPolicy { inv, _, _ ->
                if (inv.inviterId == inv.invitedId)
                    RejectionReason(RejectionReason.Code.SELF_INVITE, "invitation.reject.self_invite", "You cannot invite yourself.")
                else null
            }

        /**
         * Reject if the invited player is offline. [isOnline] answers presence (on Bukkit:
         * `Bukkit.getPlayer(id) != null`); admins bypass when [allowAdminBypass].
         */
        @JvmStatic
        @JvmOverloads
        fun <T : Invitation> targetOnline(allowAdminBypass: Boolean = true, isOnline: Presence): ValidationPolicy<T> =
            ValidationPolicy { inv, _, actor ->
                if (allowAdminBypass && actor?.admin == true) return@ValidationPolicy null
                if (isOnline.isOnline(inv.invitedId)) null
                else RejectionReason(RejectionReason.Code.TARGET_OFFLINE, "invitation.reject.target_offline", "That player is offline.")
            }

        /** Reject if the invited player is ignoring/blocking the inviter, per [ignores]. */
        @JvmStatic
        fun <T : Invitation> targetNotIgnoring(ignores: IgnoreList): ValidationPolicy<T> =
            ValidationPolicy { inv, _, _ ->
                if (ignores.ignores(inv.invitedId, inv.inviterId))
                    RejectionReason(RejectionReason.Code.TARGET_IGNORING_INVITER, "invitation.reject.ignored", "That player is not accepting invites from you.")
                else null
            }

        /** Reject if the two players already share a party/team/island, per [membership]. */
        @JvmStatic
        fun <T : Invitation> notAlreadyInSameParty(membership: PartyMembership): ValidationPolicy<T> =
            ValidationPolicy { inv, _, _ ->
                if (membership.sameParty(inv.inviterId, inv.invitedId))
                    RejectionReason(RejectionReason.Code.ALREADY_IN_SAME_PARTY, "invitation.reject.same_party", "That player is already in your party.")
                else null
            }

        /**
         * Reject if the inviter's party is full. [capacity] returns `current to max` member counts for
         * the inviter's party; null means "no party / unknown", which is allowed.
         */
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

        /** Reject if the actor lacks [node]. Admins bypass when [allowAdminBypass]. */
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

        /** Reject if the invited player lacks [node], per [permissions] (an offline-capable lookup). */
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
         * Reject when the actor's world/server isn't allowed. [allowedWorlds]/[allowedServers] null
         * means "unrestricted"; an empty set means "none allowed". A null actor world/server skips the
         * corresponding check. Admins bypass when [allowAdminBypass].
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

    /** Online-presence seam for [targetOnline]. */
    fun interface Presence {
        fun isOnline(playerId: UUID): Boolean
    }

    /** Ignore/block-list seam for [targetNotIgnoring]: does [playerId] ignore [otherId]? */
    fun interface IgnoreList {
        fun ignores(playerId: UUID, otherId: UUID): Boolean
    }

    /** Same-party seam for [notAlreadyInSameParty]. */
    fun interface PartyMembership {
        fun sameParty(a: UUID, b: UUID): Boolean
    }

    /** Party current/max member counts for [partyNotFull]; null when the player has no party. */
    fun interface PartyCapacity {
        fun capacityFor(playerId: UUID): Capacity?

        data class Capacity(val current: Int, val max: Int)
    }
}
