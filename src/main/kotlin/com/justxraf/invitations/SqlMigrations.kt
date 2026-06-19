package com.justxraf.invitations

import java.sql.Connection
import java.sql.SQLException
object SqlMigrations {
const val LATEST: Int = 1
class Step(val version: Int, val ddl: (table: String, dialect: SqlDialect) -> List<String>)

    private val steps: List<Step> = listOf(
        Step(1) { table, dialect ->
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
