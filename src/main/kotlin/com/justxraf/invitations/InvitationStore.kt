package com.justxraf.invitations

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
/**
 * Persistence SPI for invitations. The [InvitationManager] holds the authoritative in-memory state
 * and writes through to a store so invitations survive restarts. Implementations must be safe to
 * call from the manager's locked sections; see [InMemory], [JsonFileStore], [SqlInvitationStore], and
 * the [AsyncStore] wrapper.
 *
 * Extends [AutoCloseable] so pooled connections or async queues can be released from
 * [InvitationManager.shutdown].
 */
interface InvitationStore<T : Invitation> : AutoCloseable {
    /** Load every persisted invitation. Called once during [InvitationManager.rehydrate]. */
    fun load(): List<T>

    /**
     * Return every persisted invitation whose [Invitation.expiresAt] is non-null and `<= nowMillis`.
     *
     * The default scans [load], which is fine for in-memory and JSON stores. SQL-backed stores should
     * override this with a direct `WHERE expires_at <= ?` query so the manager can sweep expired rows
     * (for example invites that lapsed while the server was down) without pulling the whole table.
     */
    fun loadExpired(nowMillis: Long): List<T> =
        load().filter { it.expiresAt != null && it.expiresAt!! <= nowMillis }

    /** Persist (insert or update) a single invitation. */
    fun save(invitation: T)

    /** Remove the invitation with the given id, if present. */
    fun remove(id: UUID)

    /**
     * Remove many invitations at once. The default loops over [remove]; backends that support
     * batched writes (SQL, JSON) override this to issue a single statement/rewrite.
     */
    fun removeAll(ids: Collection<UUID>) {
        for (id in ids) remove(id)
    }

    /**
     * Atomically swap the invitation with id [old] for [new]. The default is remove-then-save;
     * transactional backends override it so the duplicate `REPLACE_EXISTING` path stays atomic.
     */
    fun replace(old: UUID, new: T) {
        remove(old)
        save(new)
    }

    /** Release any resources (connections, async queues). No-op for in-memory backends. */
    override fun close() {}

    /** Volatile, thread-safe in-memory store. Useful for tests and runtime-only invitations. */
    class InMemory<T : Invitation> : InvitationStore<T> {
        private val byId = ConcurrentHashMap<UUID, T>()
        override fun load(): List<T> = byId.values.toList()
        override fun loadExpired(nowMillis: Long): List<T> =
            byId.values.filter { it.expiresAt != null && it.expiresAt!! <= nowMillis }
        override fun save(invitation: T) { byId[invitation.id] = invitation }
        override fun remove(id: UUID) { byId.remove(id) }
        override fun removeAll(ids: Collection<UUID>) { ids.forEach { byId.remove(it) } }
        override fun replace(old: UUID, new: T) { byId.remove(old); byId[new.id] = new }
    }
}
