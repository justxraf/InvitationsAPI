package com.justxraf.invitations
interface InvitationSerializer<T : Invitation> {
fun serialize(invitation: T): Map<String, String>
fun deserialize(fields: Map<String, String>): T
}
