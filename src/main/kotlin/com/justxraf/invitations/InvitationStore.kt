package com.justxraf.invitations

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
interface InvitationStore<T : Invitation> : AutoCloseable {
fun load(): List<T>
fun save(invitation: T)
fun remove(id: UUID)
fun removeAll(ids: Collection<UUID>) {
        for (id in ids) remove(id)
    }
fun replace(old: UUID, new: T) {
        remove(old)
        save(new)
    }
override fun close() {}
class InMemory<T : Invitation> : InvitationStore<T> {
        private val byId = ConcurrentHashMap<UUID, T>()
        override fun load(): List<T> = byId.values.toList()
        override fun save(invitation: T) { byId[invitation.id] = invitation }
        override fun remove(id: UUID) { byId.remove(id) }
        override fun removeAll(ids: Collection<UUID>) { ids.forEach { byId.remove(it) } }
        override fun replace(old: UUID, new: T) { byId.remove(old); byId[new.id] = new }
    }
}
