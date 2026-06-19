# Compatibility matrix

Versions InvitationsAPI is built and tested against. The core is plain JVM/Kotlin; Bukkit/Paper/Folia
matter only if you use the `com.justxraf.invitations.bukkit` adapters.

## Toolchain (build-time)

| Component        | Version  | Notes |
| ---------------- | -------- | ----- |
| Java toolchain   | 21       | `jvmToolchain(21)` in `build.gradle.kts`; bytecode target 21. |
| Kotlin           | 2.0.21   | `kotlin("jvm")`. |
| Gradle           | wrapper  | Use the bundled `./gradlew` / `gradlew.bat`. |
| Dokka            | 1.9.20   | API docs (`./gradlew dokkaHtml`). |

## Runtime

| Surface              | Requirement | Notes |
| -------------------- | ----------- | ----- |
| Core (`com.justxraf.invitations`) | Java 21+ JVM | No server dependency; runs anywhere. |
| Kotlin stdlib        | 2.0.x       | Bundled `kotlin-stdlib`. |
| Java consumers       | Java 21+    | `@JvmStatic`/`@JvmOverloads` and `JavaInvitationHandler` provide ergonomic Java APIs; verified by `JavaSourceCompatibilityTest`. |

## Bukkit / Paper / Folia (adapters only)

| Component | Version | Notes |
| --------- | ------- | ----- |
| Paper API | 1.21.4-R0.1-SNAPSHOT | `compileOnly` — supplied by the server at runtime. |
| Minecraft | 1.21.x  | Built against 1.21.4; the adapters use stable scheduler/event APIs and should work across 1.21.x. |
| Spigot    | 1.21.x  | `BukkitScheduler` uses only the classic `BukkitScheduler`/event APIs present on Spigot. |
| Folia     | 1.21.x  | `FoliaScheduler` uses the global region scheduler (`getGlobalRegionScheduler`, `isGlobalTickThread`). |

### Choosing a scheduler

- **Spigot / Paper (non-Folia):** `BukkitScheduler(plugin)`.
- **Folia:** `FoliaScheduler(plugin)` — invitation engine work runs on the global region scheduler; see
  [adr-0001-server-free-core.md](adr-0001-server-free-core.md) and the `FoliaScheduler` KDoc.
- **Standalone / tests / proxies:** implement `Scheduler` directly (inline or thread-pool backed).

## Persistence backends

| Store               | Requirement | Notes |
| ------------------- | ----------- | ----- |
| `InvitationStore.InMemory` | none | Volatile. |
| `JsonFileStore`     | filesystem  | Single-process; optional exclusive file lock. |
| `SqlInvitationStore` (SQLite)   | JDBC driver | `SqlDialect.SQLITE`. Tests use `org.xerial:sqlite-jdbc`. |
| `SqlInvitationStore` (MySQL/MariaDB) | JDBC driver | `SqlDialect.MYSQL`. |
| `SqlInvitationStore` (PostgreSQL)    | JDBC driver | `SqlDialect.POSTGRES`. |

JDBC drivers are **not** bundled — add the driver for your backend to the consuming project. See
[sql-schema.md](sql-schema.md) for schema details.

## Test-only dependencies

These are not on the runtime classpath; listed for contributors.

| Dependency | Version | Purpose |
| ---------- | ------- | ------- |
| JUnit Jupiter | 5.10.2 | Test framework. |
| sqlite-jdbc   | 3.45.1.0 | SQL store integration tests. |
| MockBukkit (v1.21) | 4.41.0 | Bukkit event adapter tests. |
