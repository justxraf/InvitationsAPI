package com.justxraf.networkapi.invitations

/**
 * The small set of SQL differences [SqlInvitationStore] and [SqlMigrations] need across backends:
 * the column types used for the schema and the dialect-specific "insert or replace" (upsert) syntax.
 * Built-in dialects are provided for [SQLITE], [MYSQL] (MariaDB), and [POSTGRES]; supply your own for
 * anything else.
 *
 * The upsert is parameterised in this column order: `id, inviter_id, invited_id, created_at,
 * expires_at, fields`.
 */
interface SqlDialect {
    /** Column type for the text columns (`id`, party ids, `fields`). */
    val textType: String

    /** Column type for the millis columns (`created_at`, `expires_at`). */
    val bigIntType: String

    /** Upsert (insert-or-replace by primary key `id`) for [table], with 6 positional parameters. */
    fun upsertSql(table: String): String

    companion object {
        @JvmField
        val SQLITE: SqlDialect = object : SqlDialect {
            override val textType = "TEXT"
            override val bigIntType = "INTEGER"
            override fun upsertSql(table: String) =
                "INSERT OR REPLACE INTO $table (id, inviter_id, invited_id, created_at, expires_at, fields) " +
                    "VALUES (?, ?, ?, ?, ?, ?)"
        }

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
