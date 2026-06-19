package com.justxraf.networkapi.invitations

/**
 * Bridges the engine's opaque generic [T] to a concrete on-disk / on-wire form. The
 * [InvitationManager] is generic over [T] and so cannot know how to write your domain fields
 * (island id, names, request type, …); a store that persists outside the JVM delegates that to an
 * implementation of this, supplied by the plugin that owns the concrete invitation type.
 *
 * The contract is a strict round-trip: for any invitation `i`, `deserialize(serialize(i))` must
 * reproduce an equal value, including [Invitation.id], the two party ids, [Invitation.createdAt] and
 * [Invitation.expiresAt] (the manager relies on those last three to rebuild its indexes and re-arm
 * expiry on [InvitationManager.rehydrate]).
 *
 * The form is a flat `Map<String, String>` rather than a fixed format so it maps cleanly onto a JSON
 * object, a SQL row, or a YAML section without binding the core to any one library.
 */
interface InvitationSerializer<T : Invitation> {

    /** Flatten [invitation] to string fields. Must include everything [deserialize] needs to rebuild it. */
    fun serialize(invitation: T): Map<String, String>

    /** Rebuild an invitation from a map produced by [serialize]. */
    fun deserialize(fields: Map<String, String>): T
}
