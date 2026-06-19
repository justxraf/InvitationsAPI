package com.justxraf.invitations

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

private data class SqlInvite(
    override val id: UUID,
    override val inviterId: UUID,
    override val invitedId: UUID,
    val note: String,
    override val createdAt: Long,
    override val expiresAt: Long?,
) : Invitation

private object SqlInviteSerializer : InvitationSerializer<SqlInvite> {
    override fun serialize(i: SqlInvite) = mapOf(
        "id" to i.id.toString(),
        "inviter" to i.inviterId.toString(),
        "invited" to i.invitedId.toString(),
        "note" to i.note,
        "createdAt" to i.createdAt.toString(),
        "expiresAt" to (i.expiresAt?.toString() ?: ""),
    )

    override fun deserialize(f: Map<String, String>) = SqlInvite(
        id = UUID.fromString(f.getValue("id")),
        inviterId = UUID.fromString(f.getValue("inviter")),
        invitedId = UUID.fromString(f.getValue("invited")),
        note = f.getValue("note"),
        createdAt = f.getValue("createdAt").toLong(),
        expiresAt = f.getValue("expiresAt").ifEmpty { null }?.toLong(),
    )
}
class SqlInvitationStoreTest {
    private lateinit var conn: Connection

    @BeforeEach fun open() {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    @AfterEach fun close() {
        conn.close()
    }

    private fun store() = SqlInvitationStore(
        connections = { conn },
        serializer = SqlInviteSerializer,
        dialect = SqlDialect.SQLITE,
        closesConnections = false,
    )

    private fun invite(note: String = "hi", expiresAt: Long? = 5000) = SqlInvite(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), note, createdAt = 1000, expiresAt = expiresAt,
    )

    @Test fun `save then load round-trips every field including a null expiry and tricky chars`() {
        val store = store()
        val a = invite(note = "line1\nline2 \"q\" \\s\t", expiresAt = null)
        val b = invite()
        store.save(a); store.save(b)
        assertEquals(setOf(a, b), store.load().toSet())
    }

    @Test fun `save is an idempotent upsert`() {
        val store = store()
        val i = invite(note = "first")
        store.save(i)
        store.save(i.copy(note = "second"))
        assertEquals(listOf("second"), store.load().map { it.note })
        assertEquals(1, store.load().size)
    }

    @Test fun `remove and batched removeAll delete rows`() {
        val store = store()
        val keep = invite(); val drop1 = invite(); val drop2 = invite()
        listOf(keep, drop1, drop2).forEach(store::save)
        store.removeAll(listOf(drop1.id, drop2.id))
        assertEquals(listOf(keep), store.load())
    }

    @Test fun `replace swaps old for new atomically`() {
        val store = store()
        val old = invite(note = "old"); val new = invite(note = "new")
        store.save(old)
        store.replace(old.id, new)
        assertNull(store.load().firstOrNull { it.id == old.id })
        assertEquals("new", store.load().single().note)
    }

    @Test fun `migrate is idempotent and records the latest schema version`() {
        store()
        store()
        conn.prepareStatement("SELECT v FROM invitations_meta WHERE k = 'schema_version'").use { ps ->
            ps.executeQuery().use { rs ->
                assertTrue(rs.next())
                assertEquals(SqlMigrations.LATEST, rs.getInt(1))
            }
        }
    }

    @Test fun `manager rehydrates from the sql store`() {
        val inviter = UUID.randomUUID(); val invited = UUID.randomUUID()
        val live = SqlInvite(UUID.randomUUID(), inviter, invited, "x", createdAt = 0, expiresAt = 10_000)
        val sched = object : Scheduler {
            override fun now() = 0L
            override fun runOnMainThread(block: () -> Unit) = block()
            override fun runLater(delayMillis: Long, block: () -> Unit) =
                object : Scheduler.Cancellable { override fun cancel() {} }
        }
        InvitationManager(object : InvitationHandler<SqlInvite> {}, sched, store = store()).apply {
            send(live)
        }
        val m2 = InvitationManager(object : InvitationHandler<SqlInvite> {}, sched, store = store())
        assertEquals(1, m2.rehydrate())
        assertEquals(live, m2.getInvite(inviter, invited))
    }
}
