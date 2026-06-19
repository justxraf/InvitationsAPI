# InvitationsAPI

InvitationsAPI is a Kotlin library for building invitation flows: party invites, teleport requests,
trade requests, duel challenges, team joins, and similar accept/deny systems.

The core is **server-free** — it has no Bukkit dependency and runs in any JVM/Kotlin project. Bukkit
and Folia support is provided through optional adapters in the `com.justxraf.invitations.bukkit`
package. (For *why* the core stays server-free, see [docs/adr-0001-server-free-core.md](docs/adr-0001-server-free-core.md).)

## Features

- Generic invitation model with inviter, invited, created time, and optional expiry.
- Send, accept, deny, cancel, clear, and bulk operations, with typed results.
- Duplicate handling: reject, replace, or refresh the existing invite.
- Per-inviter and per-invited caps, pair cooldowns, and sliding-window rate limits.
- Optional validation policies: permissions, online checks, ignore lists, party capacity, world/server
  restrictions, and self-invite prevention.
- Expiry timers, expiry-warning callbacks, and expiry guardrails (`requireExpiry`, `maxExpiry`).
- Persistence: in-memory, JSON file, and SQL/JDBC stores, plus an async write-behind wrapper.
- Rehydration after restart, with configurable reconciliation of duplicate/invalid rows.
- Observability: pluggable logging, metrics, audit, generic observers, and vetoes.
- Bukkit event adapter, Bukkit scheduler, and Folia scheduler.

## Requirements

| Dependency | Version |
| ---------- | ------- |
| Java       | 21      |
| Kotlin JVM | 2.0.21  |
| Paper API  | 1.21.4 (only for the `bukkit/` adapters; `compileOnly`) |

See [docs/compatibility.md](docs/compatibility.md) for the full matrix.

## Build

```bash
./gradlew test       # run the test suite
./gradlew jar        # build the library jar (build/libs/)
./gradlew dokkaHtml  # generate API docs (build/dokka/html/)
```

On Windows use `gradlew.bat`.

## Install

Released to Maven Central as `com.justxraf:invitations`.

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.justxraf:invitations:0.1.0")
}
```

```xml
<!-- Maven -->
<dependency>
  <groupId>com.justxraf</groupId>
  <artifactId>invitations</artifactId>
  <version>0.1.0</version>
</dependency>
```

The published jar ships source and Javadoc artifacts. The Paper API is `compileOnly`, so plugin
consumers supply Bukkit/Paper themselves; pure-JVM consumers need nothing extra. See
[docs/versioning.md](docs/versioning.md) for the semantic-versioning and API-stability policy.

## Quick start

```kotlin
import com.justxraf.invitations.*
import java.time.Duration
import java.util.UUID

// In tests/standalone you can run everything inline; in a plugin use BukkitScheduler/FoliaScheduler.
val scheduler = object : Scheduler {
    override fun runOnMainThread(block: () -> Unit) = block()
    override fun runLater(delayMillis: Long, block: () -> Unit) =
        object : Scheduler.Cancellable { override fun cancel() = Unit }
}

val handler = object : InvitationHandler<BasicInvitation> {
    override fun onSend(invitation: BasicInvitation) = println("Invite sent")
    override fun onAccept(invitation: BasicInvitation) = println("Invite accepted")
}

val manager = InvitationManager.builder(handler, scheduler)
    .maxPerInviter(5)
    .pairCooldownMillis(Duration.ofSeconds(30).toMillis())
    .build()

val invite = Invitations.between(UUID.randomUUID(), UUID.randomUUID(), ttl = Duration.ofMinutes(2))

when (val result = manager.send(invite)) {
    is InvitationManager.SendResult.Accepted   -> manager.accept(result.invitationId)
    is InvitationManager.SendResult.Duplicate  -> println("Already invited")
    is InvitationManager.SendResult.RateLimited -> println("Try again in ${result.retryAfterMillis}ms")
    else -> println("Invite was not sent: $result")
}
```

More copy-paste snippets for both Kotlin and Java are in [docs/examples.md](docs/examples.md).

## Core types

- `Invitation` — base contract for invite data; `BasicInvitation` is a ready-to-use implementation.
- `InvitationManager<T>` — owns registration, lookup, expiry, persistence, and terminal actions. Build
  it with `InvitationManager.builder(handler, scheduler)`.
- `InvitationHandler<T>` — lifecycle callbacks (`onSend`/`onAccept`/`onDeny`/`onCancel`/`onExpire`/
  `onExpiryWarning`) plus advisory `validate`. Java callers can extend `JavaInvitationHandler<T>`.
- `Scheduler` — threading/timing seam that keeps the core server-free.
- `SendResult` / `AcceptResult` / `DenyResult` / `CancelResult` — typed outcomes; the boolean overloads
  remain for simple call sites.

## Persistence

Pick a store and pass it to the builder:

- `InvitationStore.InMemory()` — volatile, for tests or runtime-only invites.
- `JsonFileStore` — single-process file storage with atomic writes, corruption quarantine, and an
  optional exclusive file lock (`lockFile = true`).
- `SqlInvitationStore` — JDBC-backed, dependency-free over `java.sql`, with built-in `SqlDialect`s for
  SQLite, MySQL/MariaDB, and PostgreSQL. Schema is created/migrated on construction; see
  [docs/sql-schema.md](docs/sql-schema.md).
- `AsyncStore` — wraps any store with a write-behind worker so the server thread never blocks on I/O.

```kotlin
val manager = InvitationManager.builder(handler, scheduler)
    .store(AsyncStore(JsonFileStore(File(dataFolder, "invites.json"), serializer)))
    .storeFailurePolicy(StoreFailurePolicy.FAIL_BEFORE_MUTATING)
    .rehydratePolicy(RehydratePolicy.REPAIR)
    .build()

manager.rehydrate()   // load persisted invites; re-arms expiry timers
manager.sweepExpired() // expire anything that lapsed while the server was down
```

Call `shutdown()` when your application stops so timers are cancelled and the store is closed cleanly.

## Validation and abuse protection

```kotlin
val manager = InvitationManager.builder(handler, scheduler)
    .selfInvitePolicy(SelfInvitePolicy.REJECT)
    .duplicatePolicy(DuplicatePolicy.REPLACE_EXISTING)
    .maxPerInvited(3)
    .rateLimits(perInviter = RateLimiter.Limit(max = 5, windowMillis = 60_000))
    .validationPolicy(ValidationPolicy.targetOnline(isOnline = presence))
    .validationPolicy(ValidationPolicy.partyNotFull(capacity))
    .build()
```

Each `ValidationPolicy` returns a structured `RejectionReason` (with a `messageKey` for localization), so
rejected sends come back as `SendResult.PolicyRejected`. Pair cooldowns and rate limits are
runtime-only and reset on restart — see the note on `pairCooldownMillis` for making them durable.

## Observability

```kotlin
val metrics = InvitationMetrics.InMemory()
val manager = InvitationManager.builder(handler, scheduler)
    .logger(InvitationLogger.fromJul(plugin.logger))
    .metrics(metrics)
    .audit { entry -> auditTable.insert(entry) }
    .observer { event -> /* react to any lifecycle transition */ }
    .errorPolicy(LifecycleErrorPolicy.ISOLATE)
    .build()
// later: metrics.snapshot() -> sent/accepted/denied/cancelled/expired/duplicate/rejected/…
```

Logging, metrics, and audit are all wired internally as observers, so they share the same dispatch and
error-isolation path as your own observers.

## Bukkit and Folia

The `bukkit` package contains `BukkitScheduler`, `FoliaScheduler`, the cancellable invitation events,
and the `EventFiringVeto`/`EventFiringObserver` bridges. Wire them through the builder:

```kotlin
InvitationManager.builder(handler, FoliaScheduler(plugin)) // or BukkitScheduler(plugin)
    .veto(EventFiringVeto())        // fires cancellable pre-events -> *.Vetoed results
    .observer(EventFiringObserver()) // fires post-only expire/replace events
    .build()
```

The core package never references Bukkit classes.

## Documentation

- [docs/examples.md](docs/examples.md) — Kotlin & Java copy-paste examples for every primary operation.
- [docs/sql-schema.md](docs/sql-schema.md) — schema for SQLite, MySQL/MariaDB, PostgreSQL.
- [docs/compatibility.md](docs/compatibility.md) — Java/Kotlin/Paper/Folia/Minecraft compatibility matrix.
- [docs/troubleshooting.md](docs/troubleshooting.md) — store corruption, duplicate invites, scheduler
  issues, and missed expiry callbacks.
- [docs/adr-0001-server-free-core.md](docs/adr-0001-server-free-core.md) — why the core has no Bukkit
  dependency.
- [docs/versioning.md](docs/versioning.md) — semantic-versioning policy and release checklist.
- [CHANGELOG.md](CHANGELOG.md) — notable changes per release.
- API reference — `./gradlew dokkaHtml`, then open `build/dokka/html/index.html`.

In-source examples: `demo/Demo.kt`, `examples/KotlinExamples.kt`,
`src/main/java/com/justxraf/invitations/examples/JavaExamples.java`, and
`examples/BukkitAdapterExamples.kt`.

## License

Licensed under the [Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for attribution.
