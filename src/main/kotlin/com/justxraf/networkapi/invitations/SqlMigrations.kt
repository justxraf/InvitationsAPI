package com.justxraf.networkapi.invitations

import java.sql.Connection
import java.sql.SQLException

/**
 * Versioned, idempotent schema management for [SqlInvitationStore]. On construction the store calls
 * [migrate], which reads the current schema version from a `<table>_meta` row and applies every
 * pending [steps] entry inside a single transaction, bumping the recorded version as it goes. This is
 * safe to run on every startup: an already-migrated database is a no-op.
 *
 * The current schema (version [LATEST]) is:
 * ```
 * <table>(
 *   id          PRIMARY KEY,   -- invitation UUID
 *   inviter_id,                -- inviter UUID
 *   invited_id,                -- invited UUID
 *   created_at,                -- epoch millis
 *   expires_at,                -- epoch millis, nullable
 *   fields                     -- full serialized InvitationSerializer map (domain fields)
 * )
 * <table>_meta(k PRIMARY KEY, v)   -- holds k='schema_version'
 * ```
 *
 * To evolve it, append a new [Step] with the next version number and the DDL to get there; never edit
 * or reorder existing steps. See `docs/sql-schema.md` for hand-written reference DDL per backend.
 */
object SqlMigrations {

    /** The schema version this build expects. */
    const val LATEST: Int = 1

    /** One migration: bring the schema from version [version] - 1 up to [version] by running [ddl]. */
    class Step(val version: Int, val ddl: (table: String, dialect: SqlDialect) -> List<String>)

    private val steps: List<Step> = listOf(
        Step(1) { table, dialect ->
            // `fields` needs a large text type; the rest use the dialect's standard text/bigint types.
            val largeText = if (dialect === SqlDialect.MYSQL) "LONGTEXT" else dialect.textType
            listOf(
                """
                CREATE TABLE IF NOT EXISTS $table (
                    id ${dialect.textType} PRIMARY KEY,
                    inviter_id ${dialect.textType} NOT NULL,
                    invited_id ${dialect.textType} NOT NULL,
                    created_at ${dialect.bigIntType} NOT NULL,
                    expires_at ${dialect.bigIntType},
                    fields $largeText NOT NULL
                )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS ${table}_inviter_idx ON $table (inviter_id)",
                "CREATE INDEX IF NOT EXISTS ${table}_invited_idx ON $table (invited_id)",
            )
        },
    )

    fun migrate(conn: Connection, dialect: SqlDialect, table: String) {
        val metaTable = "${table}_meta"
        val previousAutoCommit = conn.autoCommit
        try {
            conn.autoCommit = false
            ensureMetaTable(conn, dialect, metaTable)
            val current = readVersion(conn, metaTable)
            for (step in steps.sortedBy { it.version }) {
                if (step.version <= current) continue
                conn.createStatement().use { st ->
                    for (sql in step.ddl(table, dialect)) st.execute(sql)
                }
                writeVersion(conn, metaTable, step.version)
            }
            conn.commit()
        } catch (t: Throwable) {
            try { conn.rollback() } catch (_: SQLException) {}
            throw t
        } finally {
            try { conn.autoCommit = previousAutoCommit } catch (_: SQLException) {}
        }
    }

    private fun ensureMetaTable(conn: Connection, dialect: SqlDialect, metaTable: String) {
        conn.createStatement().use { st ->
            st.execute(
                "CREATE TABLE IF NOT EXISTS $metaTable " +
                    "(k ${dialect.textType} PRIMARY KEY, v ${dialect.bigIntType} NOT NULL)",
            )
        }
    }

    private fun readVersion(conn: Connection, metaTable: String): Int {
        conn.prepareStatement("SELECT v FROM $metaTable WHERE k = 'schema_version'").use { ps ->
            ps.executeQuery().use { rs -> return if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    private fun writeVersion(conn: Connection, metaTable: String, version: Int) {
        conn.prepareStatement("DELETE FROM $metaTable WHERE k = 'schema_version'").use { it.executeUpdate() }
        conn.prepareStatement("INSERT INTO $metaTable (k, v) VALUES ('schema_version', ?)").use { ps ->
            ps.setInt(1, version)
            ps.executeUpdate()
        }
    }
}
