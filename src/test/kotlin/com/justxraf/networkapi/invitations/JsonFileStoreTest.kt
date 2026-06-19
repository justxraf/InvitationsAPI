package com.justxraf.networkapi.invitations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import java.util.UUID

private data class StoredInvite(
    override val id: UUID,
    override val inviterId: UUID,
    override val invitedId: UUID,
    val note: String,
    override val createdAt: Long,
    override val expiresAt: Long?,
) : Invitation

/** Round-trips every [Invitation] field plus a domain field carrying tricky characters. */
private object StoredInviteSerializer : InvitationSerializer<StoredInvite> {
    override fun serialize(i: StoredInvite): Map<String, String> = mapOf(
        "id" to i.id.toString(),
        "inviter" to i.inviterId.toString(),
        "invited" to i.invitedId.toString(),
        "note" to i.note,
        "createdAt" to i.createdAt.toString(),
        "expiresAt" to (i.expiresAt?.toString() ?: ""),
    )

    override fun deserialize(f: Map<String, String>): StoredInvite = StoredInvite(
        id = UUID.fromString(f.getValue("id")),
        inviterId = UUID.fromString(f.getValue("inviter")),
        invitedId = UUID.fromString(f.getValue("invited")),
        note = f.getValue("note"),
        createdAt = f.getValue("createdAt").toLong(),
        expiresAt = f.getValue("expiresAt").ifEmpty { null }?.toLong(),
    )
}

class JsonFileStoreTest {

    private fun invite(note: String = "hi", expiresAt: Long? = 5000) = StoredInvite(
        id = UUID.randomUUID(),
        inviterId = UUID.randomUUID(),
        invitedId = UUID.randomUUID(),
        note = note,
        createdAt = 1000,
        expiresAt = expiresAt,
    )

    @Test fun `saved invitations survive a reopen of the file`(@TempDir dir: Path) {
        val file = File(dir.toFile(), "invites.json")
        val first = invite(note = "line1\nline2 \"quoted\" \\slash\t tab")
        val second = invite(expiresAt = null)

        JsonFileStore(file, StoredInviteSerializer).apply { save(first); save(second) }

        // A fresh store over the same file must reload exactly what was written.
        val reopened = JsonFileStore(file, StoredInviteSerializer)
        assertEquals(setOf(first, second), reopened.load().toSet())
    }

    @Test fun `remove deletes from the file`(@TempDir dir: Path) {
        val file = File(dir.toFile(), "invites.json")
        val keep = invite(); val drop = invite()
        val store = JsonFileStore(file, StoredInviteSerializer)
        store.save(keep); store.save(drop)

        store.remove(drop.id)

        assertEquals(listOf(keep), JsonFileStore(file, StoredInviteSerializer).load())
    }

    @Test fun `a missing file loads as empty`(@TempDir dir: Path) {
        val store = JsonFileStore(File(dir.toFile(), "nested/invites.json"), StoredInviteSerializer)
        assertTrue(store.load().isEmpty())
    }

    @Test fun `manager rehydrates from a file store across a simulated restart`(@TempDir dir: Path) {
        val file = File(dir.toFile(), "invites.json")
        val inviter = UUID.randomUUID(); val invited = UUID.randomUUID()
        val live = StoredInvite(UUID.randomUUID(), inviter, invited, "x", createdAt = 0, expiresAt = 10_000)

        // Run 1: send through a manager, then "stop".
        val sched1 = object : Scheduler {
            override fun now() = 0L
            override fun runOnMainThread(block: () -> Unit) = block()
            override fun runLater(delayMillis: Long, block: () -> Unit) =
                object : Scheduler.Cancellable { override fun cancel() {} }
        }
        val m1 = InvitationManager(
            object : InvitationHandler<StoredInvite> {}, sched1,
            store = JsonFileStore(file, StoredInviteSerializer),
        )
        m1.send(live)
        m1.shutdown()

        // Run 2: a brand-new manager over the same file rebuilds its state.
        val m2 = InvitationManager(
            object : InvitationHandler<StoredInvite> {}, sched1,
            store = JsonFileStore(file, StoredInviteSerializer),
        )
        assertEquals(1, m2.rehydrate())
        assertEquals(live, m2.getInvite(inviter, invited))
    }
}
