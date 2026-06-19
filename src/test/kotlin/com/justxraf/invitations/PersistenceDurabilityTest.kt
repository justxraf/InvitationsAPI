package com.justxraf.invitations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.UUID

private data class DurInvite(
    override val id: UUID,
    override val inviterId: UUID,
    override val invitedId: UUID,
    override val createdAt: Long,
    override val expiresAt: Long?,
) : Invitation

private object DurSerializer : InvitationSerializer<DurInvite> {
    override fun serialize(i: DurInvite) = mapOf(
        "id" to i.id.toString(),
        "inviter" to i.inviterId.toString(),
        "invited" to i.invitedId.toString(),
        "createdAt" to i.createdAt.toString(),
        "expiresAt" to (i.expiresAt?.toString() ?: ""),
    )

    override fun deserialize(f: Map<String, String>) = DurInvite(
        UUID.fromString(f.getValue("id")),
        UUID.fromString(f.getValue("inviter")),
        UUID.fromString(f.getValue("invited")),
        f.getValue("createdAt").toLong(),
        f.getValue("expiresAt").ifEmpty { null }?.toLong(),
    )
}
private class FlakyStore<T : Invitation> : InvitationStore<T> {
    private val backing = InvitationStore.InMemory<T>()
    var failSaves = false
    var saveAttempts = 0
    override fun load() = backing.load()
    override fun save(invitation: T) {
        saveAttempts++
        if (failSaves) throw RuntimeException("boom")
        backing.save(invitation)
    }
    override fun remove(id: UUID) = backing.remove(id)
}

private val immediateScheduler = object : Scheduler {
    override fun now() = 0L
    override fun runOnMainThread(block: () -> Unit) = block()
    override fun runLater(delayMillis: Long, block: () -> Unit) =
        object : Scheduler.Cancellable { override fun cancel() {} }
}

class PersistenceDurabilityTest {

    private fun invite(inviter: UUID = UUID.randomUUID(), invited: UUID = UUID.randomUUID(), createdAt: Long = 0) =
        DurInvite(UUID.randomUUID(), inviter, invited, createdAt, expiresAt = null)

    @Test fun `FAIL_BEFORE_MUTATING rolls the index back on a store failure`() {
        val store = FlakyStore<DurInvite>().apply { failSaves = true }
        val m = InvitationManager(
            object : InvitationHandler<DurInvite> {},
            immediateScheduler,
            store = store,
            storeFailurePolicy = StoreFailurePolicy.FAIL_BEFORE_MUTATING,
        )
        val result = m.send(invite())
        assertInstanceOf(InvitationManager.SendResult.StoreFailure::class.java, result)
        assertEquals(0, m.pendingCount(), "a failed store write must leave no in-memory trace")
        assertTrue(m.isHealthy())
    }

    @Test fun `MUTATE_THEN_RETRY keeps the invite in memory and retries the write`() {
        val store = FlakyStore<DurInvite>().apply { failSaves = true }
        val m = InvitationManager(
            object : InvitationHandler<DurInvite> {},
            immediateScheduler,
            store = store,
            storeFailurePolicy = StoreFailurePolicy.MUTATE_THEN_RETRY,
            storeWriteRetries = 2,
        )
        val result = m.send(invite())
        assertInstanceOf(InvitationManager.SendResult.Accepted::class.java, result)
        assertEquals(1, m.pendingCount())
        assertEquals(3, store.saveAttempts, "1 initial + 2 retries")
        assertTrue(m.isHealthy())
    }

    @Test fun `MARK_UNHEALTHY flips health when the store keeps failing`() {
        val store = FlakyStore<DurInvite>().apply { failSaves = true }
        val m = InvitationManager(
            object : InvitationHandler<DurInvite> {},
            immediateScheduler,
            store = store,
            storeFailurePolicy = StoreFailurePolicy.MARK_UNHEALTHY,
            storeWriteRetries = 1,
        )
        m.send(invite())
        assertFalse(m.isHealthy())
    }

    private fun manager(store: InvitationStore<DurInvite>, policy: RehydratePolicy, maxPerInviter: Int? = null) =
        InvitationManager(
            object : InvitationHandler<DurInvite> {},
            immediateScheduler,
            maxPerInviter = maxPerInviter,
            store = store,
            rehydratePolicy = policy,
        )

    @Test fun `REPAIR drops a duplicate pair keeping the newest and heals the store`() {
        val inviter = UUID.randomUUID(); val invited = UUID.randomUUID()
        val older = invite(inviter, invited, createdAt = 100)
        val newer = invite(inviter, invited, createdAt = 200)
        val store = InvitationStore.InMemory<DurInvite>().apply { save(older); save(newer) }

        val m = manager(store, RehydratePolicy.REPAIR)
        assertEquals(1, m.rehydrate())
        assertEquals(newer, m.getInvite(inviter, invited))
        assertEquals(listOf(newer), store.load(), "store should be healed of the duplicate")
    }

    @Test fun `TRUST_STORE keeps both rows of a duplicate pair`() {
        val inviter = UUID.randomUUID(); val invited = UUID.randomUUID()
        val store = InvitationStore.InMemory<DurInvite>().apply {
            save(invite(inviter, invited, createdAt = 100))
            save(invite(inviter, invited, createdAt = 200))
        }
        val m = manager(store, RehydratePolicy.TRUST_STORE)
        assertEquals(2, m.rehydrate())
    }

    @Test fun `enforceCaps drops the oldest over the inviter cap`() {
        val inviter = UUID.randomUUID()
        val store = InvitationStore.InMemory<DurInvite>().apply {
            save(invite(inviter, createdAt = 1))
            save(invite(inviter, createdAt = 2))
            save(invite(inviter, createdAt = 3))
        }
        val m = manager(store, RehydratePolicy.REPAIR, maxPerInviter = 2)
        assertEquals(2, m.rehydrate())
        assertEquals(2, m.countFrom(inviter))
        assertEquals(setOf(2L, 3L), m.getInvitesFrom(inviter).map { it.createdAt }.toSet())
    }

    @Test fun `DROP_INVALID drops in memory but leaves the store untouched`() {
        val inviter = UUID.randomUUID(); val invited = UUID.randomUUID()
        val store = InvitationStore.InMemory<DurInvite>().apply {
            save(invite(inviter, invited, createdAt = 100))
            save(invite(inviter, invited, createdAt = 200))
        }
        val m = manager(store, RehydratePolicy.DROP_INVALID)
        assertEquals(1, m.rehydrate())
        assertEquals(2, store.load().size, "DROP_INVALID must not repair the store")
    }

    @Test fun `a corrupt json file is quarantined and the store starts empty`(
        @TempDir dir: Path,
    ) {
        val file = File(dir.toFile(), "invites.json")
        file.writeText("this is not json {{{")
        val store = JsonFileStore(file, DurSerializer, lockFile = false)
        assertTrue(store.load().isEmpty())
        val quarantined = dir.toFile().listFiles { f -> f.name.startsWith("invites.json.corrupt-") }
        assertTrue(quarantined != null && quarantined.isNotEmpty(), "corrupt file should be moved aside")
        store.close()
    }

    @Test fun `recoverFromCorruption=false throws on a corrupt file`(
        @TempDir dir: Path,
    ) {
        val file = File(dir.toFile(), "invites.json")
        file.writeText("garbage")
        assertThrows(java.io.IOException::class.java) {
            JsonFileStore(file, DurSerializer, recoverFromCorruption = false, lockFile = false)
        }
    }

    @Test fun `a second JsonFileStore over the same locked file is rejected`(
        @TempDir dir: Path,
    ) {
        val file = File(dir.toFile(), "invites.json")
        val first = JsonFileStore(file, DurSerializer, lockFile = true)
        assertThrows(IllegalStateException::class.java) {
            JsonFileStore(file, DurSerializer, lockFile = true)
        }
        first.close()
        JsonFileStore(file, DurSerializer, lockFile = true).close()
    }

    @Test fun `JsonFileStore replace and removeAll round-trip`(
        @TempDir dir: Path,
    ) {
        val file = File(dir.toFile(), "invites.json")
        val store = JsonFileStore(file, DurSerializer, lockFile = false)
        val a = invite(); val b = invite(); val c = invite()
        listOf(a, b, c).forEach(store::save)
        store.removeAll(listOf(a.id, b.id))
        assertEquals(listOf(c.id), store.load().map { it.id })
        val d = invite()
        store.replace(c.id, d)
        store.close()
        assertEquals(listOf(d.id), JsonFileStore(file, DurSerializer, lockFile = false).load().map { it.id })
    }

    @Test fun `AsyncStore applies queued writes by load() and survives close()`() {
        val delegate = InvitationStore.InMemory<DurInvite>()
        val async = AsyncStore(delegate)
        val a = invite(); val b = invite()
        async.save(a); async.save(b)
        async.remove(a.id)
        assertEquals(listOf(b.id), async.load().map { it.id })
        async.close()
        assertEquals(listOf(b.id), delegate.load().map { it.id })
    }
}
