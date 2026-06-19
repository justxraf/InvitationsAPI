package com.justxraf.networkapi.invitations

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Durability seam that keeps the core API free of any storage dependency, mirroring the [Scheduler]
 * seam. An [InvitationManager] writes through to its store on every mutation (send / terminal) and
 * reads back from it once, on startup, via [InvitationManager.rehydrate].
 *
 * Implementations must be safe to call from the manager's locking sections, so keep them quick and
 * non-blocking where possible (or hand off to your own executor — see [AsyncStore]). The default
 * [InMemory] adapter is a no-op-durable store useful for tests and for plugins that don't need to
 * survive a restart yet; [JsonFileStore] is a file-backed adapter built on an [InvitationSerializer]
 * and [SqlInvitationStore] is a JDBC adapter for higher volumes.
 *
 * The store is the source of truth across restarts: [load] returns everything that was pending when
 * the server last stopped, including invitations whose [Invitation.expiresAt] has since passed —
 * [InvitationManager.rehydrate] is responsible for expiring those (and for applying its configured
 * [RehydratePolicy]), not the store.
 *
 * ### Failure contract
 * [save], [remove], [removeAll] and [replace] may throw to signal a durability failure. How the
 * manager reacts is governed by its [StoreFailurePolicy]; a store should surface real failures rather
 * than swallowing them so that policy can take effect.
 *
 * ### Lifecycle
 * Stores that hold OS resources (file handles, JDBC connections, async queues) implement [close] to
 * release them; the manager calls it from [InvitationManager.shutdown]. [InMemory] needs no cleanup.
 */
interface InvitationStore<T : Invitation> : AutoCloseable {

    /** Every persisted, not-yet-consumed invitation. Called once on startup. Order is not significant. */
    fun load(): List<T>

    /** Persist (insert or replace) one invitation, keyed by [Invitation.id]. May throw on failure. */
    fun save(invitation: T)

    /** Drop the invitation with this id. No-op if it isn't stored. May throw on failure. */
    fun remove(id: UUID)

    /**
     * Drop many invitations in one shot. The default routes to [remove] per id, but durable stores
     * should override with a single batched write so bulk operations ([InvitationManager.denyAll],
     * [InvitationManager.cancelAllFrom], [InvitationManager.clearAllFor]) don't issue N round-trips.
     * Ids that aren't stored are ignored.
     */
    fun removeAll(ids: Collection<UUID>) {
        for (id in ids) remove(id)
    }

    /**
     * Atomically swap [old] for [new] — remove the old id and persist the new one as a single unit
     * where the backend supports it. Used by the duplicate `REPLACE_EXISTING` policy. The default is
     * a non-atomic remove-then-save; transactional stores should override.
     */
    fun replace(old: UUID, new: T) {
        remove(old)
        save(new)
    }

    /** Release any resources (file handles, connections, async queues). No-op by default. */
    override fun close() {}

    /**
     * A volatile, in-process store. Durable only for the lifetime of the JVM — exactly the previous
     * behaviour, surfaced as a store so the manager always has one. Backed by a concurrent map.
     */
    class InMemory<T : Invitation> : InvitationStore<T> {
        private val byId = ConcurrentHashMap<UUID, T>()
        override fun load(): List<T> = byId.values.toList()
        override fun save(invitation: T) { byId[invitation.id] = invitation }
        override fun remove(id: UUID) { byId.remove(id) }
        override fun removeAll(ids: Collection<UUID>) { ids.forEach { byId.remove(it) } }
        override fun replace(old: UUID, new: T) { byId.remove(old); byId[new.id] = new }
    }
}
