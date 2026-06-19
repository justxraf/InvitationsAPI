package com.justxraf.networkapi.invitations

import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/**
 * A JDBC-backed [InvitationStore] for plugins that outgrow [JsonFileStore] — hundreds of thousands of
 * pending invitations, or invitations that must be shared across a network of servers via a central
 * database. It is dependency-free: it talks the standard [java.sql] API, so any JDBC driver on the
 * classpath (SQLite, MySQL/MariaDB, PostgreSQL) works.
 *
 * ### Connections
 * The store does not own a connection pool. It takes a [connections] supplier — typically your pool's
 * `getConnection` — and borrows one connection per operation, closing it (returning it to the pool)
 * when done. For an unpooled single-connection setup (e.g. embedded SQLite), supply a function that
 * returns the same long-lived connection each time and pass `closesConnections = false` so the store
 * does not close it between operations; [close] then closes it once.
 *
 * ### Schema
 * One row per invitation in a table (default `invitations`) with the four core columns the manager
 * relies on — `id`, `inviter_id`, `invited_id`, `created_at`, `expires_at` — plus a `fields` text
 * column holding the full serialized [InvitationSerializer] map so domain fields round-trip without
 * the core needing to know them. A `meta` table records the schema version; [migrate] is run once on
 * construction and applies any pending [SqlMigrations] steps inside a transaction.
 *
 * ### Batching & atomicity
 * [removeAll] deletes in a single batched statement; [replace] runs the delete and insert in one
 * transaction. [save] uses the [SqlDialect] upsert so a re-save is idempotent.
 */
class SqlInvitationStore<T : Invitation> @JvmOverloads constructor(
    private val connections: () -> Connection,
    private val serializer: InvitationSerializer<T>,
    private val dialect: SqlDialect,
    private val table: String = "invitations",
    private val closesConnections: Boolean = true,
) : InvitationStore<T> {

    init {
        require(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Unsafe table name: $table" }
        withConnection { conn -> SqlMigrations.migrate(conn, dialect, table) }
    }

    override fun load(): List<T> = withConnection { conn ->
        conn.prepareStatement("SELECT fields FROM $table").use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(serializer.deserialize(FieldCodec.decode(rs.getString(1))))
                }
            }
        }
    }

    override fun save(invitation: T) = withConnection { conn ->
        upsert(conn, invitation)
    }

    override fun remove(id: UUID) = withConnection { conn ->
        conn.prepareStatement("DELETE FROM $table WHERE id = ?").use { ps ->
            ps.setString(1, id.toString())
            ps.executeUpdate()
        }
        Unit
    }

    override fun removeAll(ids: Collection<UUID>) {
        if (ids.isEmpty()) return
        withConnection { conn ->
            conn.prepareStatement("DELETE FROM $table WHERE id = ?").use { ps ->
                for (id in ids) {
                    ps.setString(1, id.toString())
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    override fun replace(old: UUID, new: T) {
        inTransaction { conn ->
            conn.prepareStatement("DELETE FROM $table WHERE id = ?").use { ps ->
                ps.setString(1, old.toString())
                ps.executeUpdate()
            }
            upsert(conn, new)
        }
    }

    /** No-op for pooled use (each op closes its borrowed connection). Closes the shared connection otherwise. */
    override fun close() {
        if (!closesConnections) {
            try { connections().close() } catch (_: SQLException) {}
        }
    }

    private fun upsert(conn: Connection, invitation: T) {
        val fields = serializer.serialize(invitation)
        conn.prepareStatement(dialect.upsertSql(table)).use { ps ->
            ps.setString(1, invitation.id.toString())
            ps.setString(2, invitation.inviterId.toString())
            ps.setString(3, invitation.invitedId.toString())
            ps.setLong(4, invitation.createdAt)
            if (invitation.expiresAt != null) ps.setLong(5, invitation.expiresAt!!) else ps.setNull(5, java.sql.Types.BIGINT)
            ps.setString(6, FieldCodec.encode(fields))
            ps.executeUpdate()
        }
    }

    private inline fun <R> withConnection(block: (Connection) -> R): R {
        val conn = connections()
        return try {
            block(conn)
        } finally {
            if (closesConnections) conn.close()
        }
    }

    private inline fun inTransaction(block: (Connection) -> Unit) {
        val conn = connections()
        val previousAutoCommit = conn.autoCommit
        try {
            conn.autoCommit = false
            block(conn)
            conn.commit()
        } catch (t: Throwable) {
            try { conn.rollback() } catch (_: SQLException) {}
            throw t
        } finally {
            try { conn.autoCommit = previousAutoCommit } catch (_: SQLException) {}
            if (closesConnections) conn.close()
        }
    }
}
