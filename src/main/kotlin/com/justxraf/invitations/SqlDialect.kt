package com.justxraf.invitations
interface SqlDialect {
val textType: String
val bigIntType: String
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
