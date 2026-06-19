package com.justxraf.invitations
/**
 * Converts an invitation [T] to and from a flat string→string field map. This is how durable stores
 * persist a concrete subtype's payload without depending on its class: the map is encoded by
 * [FieldCodec] into the JSON file or the SQL `fields` column and decoded back on load.
 */
interface InvitationSerializer<T : Invitation> {
    /** Flatten an invitation (including any subtype fields) into a string map. */
    fun serialize(invitation: T): Map<String, String>

    /** Reconstruct an invitation from a previously [serialize]d field map. */
    fun deserialize(fields: Map<String, String>): T
}
