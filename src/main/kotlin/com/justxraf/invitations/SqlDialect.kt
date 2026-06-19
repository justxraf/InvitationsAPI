package com.justxraf.invitations
/**
 * Per-backend SQL fragments that let [SqlInvitationStore] stay dependency-free over `java.sql` while
 * still using each database's native upsert. Built-in dialects cover [SQLITE], [MYSQL], and
 * [POSTGRES]; supply your own to target another backend.
 */
interface SqlDialect {
    /** Column type used for UUID/string columns (`TEXT`, `VARCHAR(255)`, …). */
    val textType: String

    /** Column type used for timestamp columns (`INTEGER`, `BIGINT`, …). */
    val bigIntType: String

    /** Insert-or-update statement for [table], with the six positional parameters in column order. */
    fun upsertSql(table: String): String

    companion object {
        /** SQLite dialect using `INSERT OR REPLACE`. */
        @JvmField
        val SQLITE: SqlDialect = object : SqlDialect {
            override val textType = "TEXT"
            override val bigIntType = "INTEGER"
            override fun upsertSql(table: String) =
                "INSERT OR REPLACE INTO $table (id, inviter_id, invited_id, created_at, expires_at, fields) " +
                    "VALUES (?, ?, ?, ?, ?, ?)"
        }

        /** MySQL/MariaDB dialect using `INSERT … ON DUPLICATE KEY UPDATE`. */
        @JvmField
        val MYSQL: SqlDialect = object : SqlDialect {
            override val textType = "VARCHAR(255)"
            override val bigIntType = "BIGINT"
            override fun upsertSql(table: String) =
                "INSERT INTO $table (id, inviter_id, invited_id, created_at, expires_at, fields) " +
                    "VALUES (?, ?, ?, ?, ?, ?) AS new " +
                    "ON DUPLICATE KEY UPDATE inviter_id = new.inviter_id, invited_id = new.invited_id, " +
                    "created_at = new.created_at, expires_at = new.expires_at, fields = new.fields"
        }

        /** PostgreSQL dialect using `INSERT … ON CONFLICT (id) DO UPDATE`. */
        @JvmField
        val POSTGRES: SqlDialect = object : SqlDialect {
            override val textType = "TEXT"
            override val bigIntType = "BIGINT"
            override fun upsertSql(table: String) =
                "INSERT INTO $table (id, inviter_id, invited_id, created_at, expires_at, fields) " +
                    "VALUES (?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (id) DO UPDATE SET inviter_id = EXCLUDED.inviter_id, " +
                    "invited_id = EXCLUDED.invited_id, created_at = EXCLUDED.created_at, " +
                    "expires_at = EXCLUDED.expires_at, fields = EXCLUDED.fields"
        }
    }
}
