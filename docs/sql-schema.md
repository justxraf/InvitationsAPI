# SQL schema for `SqlInvitationStore`

`SqlInvitationStore` creates and migrates its own schema on startup (see `SqlMigrations`), so you do
**not** normally need to run any DDL by hand — point it at a `Connection` supplier and a `SqlDialect`
and it bootstraps the tables. The reference DDL below is provided for DBAs who want to provision the
tables ahead of time, review what the store creates, or grant least-privilege access.

The store keeps the five core columns the `InvitationManager` relies on, plus a `fields` text column
holding the full serialized `InvitationSerializer` map (the domain fields), so any invitation type
round-trips without a schema change. A companion `<table>_meta` table records the schema version.

Default table name is `invitations`; substitute your own if you passed a custom `table`.

## SQLite (`SqlDialect.SQLITE`)

```sql
CREATE TABLE IF NOT EXISTS invitations (
    id          TEXT    PRIMARY KEY,
    inviter_id  TEXT    NOT NULL,
    invited_id  TEXT    NOT NULL,
    created_at  INTEGER NOT NULL,   -- epoch millis
    expires_at  INTEGER,            -- epoch millis, NULL = never expires
    fields      TEXT    NOT NULL    -- serialized domain fields
);
CREATE INDEX IF NOT EXISTS invitations_inviter_idx ON invitations (inviter_id);
CREATE INDEX IF NOT EXISTS invitations_invited_idx ON invitations (invited_id);

CREATE TABLE IF NOT EXISTS invitations_meta (
    k TEXT    PRIMARY KEY,
    v INTEGER NOT NULL
);
```

Upsert uses `INSERT OR REPLACE`. For an embedded single-connection setup, construct the store with
`closesConnections = false` and supply a function returning the one long-lived `Connection`.

## MySQL / MariaDB (`SqlDialect.MYSQL`)

```sql
CREATE TABLE IF NOT EXISTS invitations (
    id          VARCHAR(255) PRIMARY KEY,
    inviter_id  VARCHAR(255) NOT NULL,
    invited_id  VARCHAR(255) NOT NULL,
    created_at  BIGINT       NOT NULL,
    expires_at  BIGINT,
    fields      LONGTEXT     NOT NULL
);
CREATE INDEX invitations_inviter_idx ON invitations (inviter_id);
CREATE INDEX invitations_invited_idx ON invitations (invited_id);

CREATE TABLE IF NOT EXISTS invitations_meta (
    k VARCHAR(255) PRIMARY KEY,
    v BIGINT       NOT NULL
);
```

Upsert uses the `INSERT ... AS new ON DUPLICATE KEY UPDATE ...` form, which requires **MySQL
8.0.19+** or **MariaDB 10.3.3+**. On older servers, override `SqlDialect.upsertSql` with the legacy
`VALUES()` alias syntax.

`MySQL`/`MariaDB` ignore `CREATE INDEX IF NOT EXISTS` on some versions — provision indexes once, or
let the store create them on first run against a fresh schema.

## PostgreSQL (`SqlDialect.POSTGRES`)

```sql
CREATE TABLE IF NOT EXISTS invitations (
    id          TEXT   PRIMARY KEY,
    inviter_id  TEXT   NOT NULL,
    invited_id  TEXT   NOT NULL,
    created_at  BIGINT NOT NULL,
    expires_at  BIGINT,
    fields      TEXT   NOT NULL
);
CREATE INDEX IF NOT EXISTS invitations_inviter_idx ON invitations (inviter_id);
CREATE INDEX IF NOT EXISTS invitations_invited_idx ON invitations (invited_id);

CREATE TABLE IF NOT EXISTS invitations_meta (
    k TEXT   PRIMARY KEY,
    v BIGINT NOT NULL
);
```

Upsert uses `INSERT ... ON CONFLICT (id) DO UPDATE SET ...`.

## Migrations

`SqlMigrations.LATEST` is the schema version this build expects. On construction the store reads the
recorded version from `invitations_meta` (`k = 'schema_version'`) and applies, inside one
transaction, every `SqlMigrations.Step` whose version is greater — then records the new version.
Running against an up-to-date database is a no-op, so it is safe to call on every startup.

To evolve the schema, **append** a new `Step(N) { table, dialect -> listOf(ddl...) }` with the next
version number; never edit or reorder existing steps, since deployed databases have already applied
them.
