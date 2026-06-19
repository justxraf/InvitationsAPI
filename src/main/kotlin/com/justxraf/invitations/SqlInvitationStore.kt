package com.justxraf.invitations

import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
/**
 * JDBC-backed [InvitationStore], dependency-free over `java.sql`. Each invitation is one row with the
 * core columns (`id`, `inviter_id`, `invited_id`, `created_at`, `expires_at`) plus a serialized
 * `fields` blob produced by [InvitationSerializer]/[FieldCodec], so subtype payload survives without a
 * schema change. The schema is created/upgraded by [SqlMigrations] on construction.
 *
 * @param connections supplies a JDBC [Connection] per operation; with a pool, return a pooled one.
 * @param serializer converts invitations to/from the flat field map stored in the `fields` column.
 * @param dialect backend-specific SQL (upsert, column types); see [SqlDialect].
 * @param table table name; validated against an identifier pattern since it is interpolated into SQL.
 * @param closesConnections whether to `close()` each connection after use — `false` for pooled setups
 *   that manage their own lifecycle.
 */
class SqlInvitationStore<T : Invitation> @JvmOverloads constructor(
    private val connections: () -> Connection,
    private val serializer: InvitationSerializer<T>,
    private val dialect: SqlDialect,
    private val table: String = "invitations",
    private val closesConnections: Boolean = true,
) : InvitationStore<T> {

    init {
        // The table name is interpolated into SQL, so keep it stricter than a normal config string.
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

    override fun loadExpired(nowMillis: Long): List<T> = withConnection { conn ->
        conn.prepareStatement("SELECT fields FROM $table WHERE expires_at IS NOT NULL AND expires_at <= ?").use { ps ->
            ps.setLong(1, nowMillis)
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
        // Duplicate replacement should never leave both the old and new invitations visible.
        inTransaction { conn ->
            conn.prepareStatement("DELETE FROM $table WHERE id = ?").use { ps ->
                ps.setString(1, old.toString())
                ps.executeUpdate()
            }
            upsert(conn, new)
        }
    }
    override fun close() {
        if (!closesConnections) {
            try {
                connections().close()
            } catch (_: SQLException) {
            }
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
            try {
                conn.rollback()
            } catch (_: SQLException) {
            }
            throw t
        } finally {
            try {
                conn.autoCommit = previousAutoCommit
            } catch (_: SQLException) {
            }
            if (closesConnections) conn.close()
        }
    }
}
