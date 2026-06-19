# Invitations API - production readiness roadmap

The prototype already has a strong reusable core: a generic `InvitationManager<T>`, reverse indexes
by inviter and invited player, pair de-duplication, cancellable expiry timers, a Bukkit-free
`Scheduler`, lifecycle hooks, an `InvitationStore` SPI, JSON file persistence, rehydration, bulk
operations, a builder, Java handler support, and unit tests.

This roadmap lists the remaining work needed before this can be shipped as a production NetworkAPI
module and safely adopted by IslandCore, SkyblockAPI, and future plugins.

---

## 0. Current Baseline

- [x] Generic `Invitation` model with id, inviter, invited, created time, and optional expiry time.
- [x] `InvitationManager<T>` owns registration, lookup, de-duplication, expiry, accept, deny, cancel,
  clear, and bulk terminal operations.
- [x] `InvitationHandler<T>` lifecycle hooks for send, accept, deny, cancel, expire, and validate.
- [x] `JavaInvitationHandler<T>` for Java callers that only want to override selected hooks.
- [x] `Scheduler` seam with a Bukkit adapter and server-free tests.
- [x] `InvitationStore<T>` SPI with volatile in-memory storage and `JsonFileStore`.
- [x] Startup `rehydrate()` and plugin-disable `shutdown()` semantics.
- [x] Builder entry point for Java-friendly, non-breaking option growth.
- [x] Unit coverage for lifecycle, indexes, expiry, persistence, rehydration, bulk operations, and
  builder defaults.

---

## 1. Public API Completeness

- [x] Add `maxPerInvited` to cap simultaneous pending invitations addressed to the same player.
- [x] Add per-pair cooldowns to prevent repeated invite spam after deny, cancel, expire, or duplicate
  attempts.
- [x] Add a duplicate policy:
  - `REJECT_EXISTING` keeps the current behavior.
  - `REPLACE_EXISTING` consumes the old invite and registers the new one.
  - `REFRESH_EXPIRY` keeps the same logical invite but extends its expiry.
- [x] Add self-invite policy handling (`inviterId == invitedId`) with either a built-in rejection or
  an explicit builder option. (`SelfInvitePolicy`, defaults to `REJECT`; `SendResult.SelfInvite`.)
- [x] Add optional expiry warning callbacks, such as `onExpiryWarning(invitation, remainingMillis)`,
  with one or more configurable warning offsets. (`Builder.expiryWarningOffsetsMillis`.)
- [x] Add `Duration`-based helpers so callers do not need to calculate epoch millis manually.
  (`Invitations.expiresAt(Duration)`, `Invitations.between(..., ttl)`.)
- [x] Add a factory/helper for common invitation creation fields: id generation, `createdAt`,
  `expiresAt`, and clock source. (`Invitations` + concrete `BasicInvitation`.)
- [x] Add `getInvitesBetween(playerA, playerB)` for systems where direction may not matter.
- [x] Add stable sorted query variants:
  - `getInvitesFor(invitedId, sort = NEWEST_FIRST)`
  - `getInvitesFrom(inviterId, sort = NEWEST_FIRST)`
  - `all(sort = NEWEST_FIRST)`
- [x] Add lightweight count helpers: `countFor(invitedId)`, `countFrom(inviterId)`, and
  `pendingCount()`.
- [x] Add idempotent terminal results instead of bare booleans, for example `AcceptResult`,
  `DenyResult`, and `CancelResult` with `Accepted`/`Denied`/`Cancelled` or `NotFound`. (Exposed via
  the `*Detailed` variants; the boolean overloads remain. `RejectedByEvent` waits on §2 Bukkit events.)
- [x] Add `SendResult` metadata for replaced/refreshed invitations once duplicate policies exist.
- [x] Add richer cancellation reasons beyond `REVOKED` and `PARTY_CLEARED`, such as
  `PLAYER_QUIT`, `SERVER_SHUTDOWN`, `TARGET_LIMIT_REACHED`, `DUPLICATE_REPLACED`, and
  `ADMIN_CLEARED`.
- [x] Decide whether `clearFor(playerId)` should remove both directions by default forever, or split
  it into clearer methods: `clearAsInviter`, `clearAsInvited`, and `clearAllFor`. (Split into all
  three reason-aware methods; `clearFor` kept as a both-directions / `PARTY_CLEARED` alias.)
- [x] Add Kotlin and Java examples for every primary operation. (`examples/KotlinExamples.kt`,
  `examples/JavaExamples.java` — the latter doubles as a Java source-compatibility check.)

## 2. Handler, Events, And Extension Points

- [x] Add Bukkit event classes in the `bukkit/` package while keeping the core server-free:
  `InvitationSendEvent`, `InvitationAcceptEvent`, `InvitationDenyEvent`, `InvitationCancelEvent`,
  `InvitationExpireEvent`, and optionally `InvitationReplaceEvent`. (`bukkit/InvitationEvents.kt`;
  the core depends only on the `InvitationObserver`/`InvitationVeto` seams, never `org.bukkit.event`.)
- [x] Make send/accept/deny/cancel events cancellable where it makes sense, and map cancellations
  into typed manager results. (Pre-events are `Cancellable`; `EventFiringVeto` reports `isCancelled`
  back, yielding `SendResult.Vetoed` / `AcceptResult.Vetoed` / `DenyResult.Vetoed` / `CancelResult.Vetoed`.)
- [x] Define ordering between handler hooks and Bukkit events:
  - event before state mutation for vetoable actions, (veto runs before `index`/`unindex`)
  - state mutation before irreversible side effects, (store write + index commit before hooks)
  - handler callback once per successful lifecycle transition. (`fireHook` → `notifyObservers`, once each)
- [x] Add a generic listener/observer seam in core for non-Bukkit environments. (`InvitationObserver`
  + `LifecycleEvent`; the Bukkit adapters are just observers/vetoes over it.)
- [x] Add pluggable logging, preferably SLF4J or a tiny logger interface accepted by the builder.
  (`InvitationLogger` SPI with a JUL adapter and `Builder.logger(...)`; wired as `LoggingObserver`.)
- [x] Add metrics hooks/counters for pending, sent, accepted, denied, cancelled, expired, duplicate,
  rejected, and store failures. (`InvitationMetrics` + `InMemory` snapshot; lifecycle counters via
  `MetricsObserver`, `recordSendOutcome` for duplicate/rejected/limit/cooldown/self-invite,
  `recordStoreFailure` for persistence errors.)
- [x] Add an optional audit sink for production debugging: who invited whom, outcome, reason,
  timestamps, and invite type. (`InvitationAudit` + flat `AuditEntry`; wired as `AuditObserver`.)
- [x] Define exception policy for handler callbacks and event listeners:
  log and continue, propagate, or route to an error callback. (`LifecycleErrorPolicy.ISOLATE` /
  `PROPAGATE`; every hook/observer runs through `guarded(...)`.)
- [x] Add an error callback such as `onLifecycleError(invitation, action, throwable)` if callbacks are
  isolated from callers. (`LifecycleErrorCallback`, fired regardless of policy; `Builder.errorCallback(...)`.)

## 3. Persistence And Durability

- [x] Add a SQL/JDBC `InvitationStore` implementation. (`SqlInvitationStore`, dependency-free over
  `java.sql`; one row per invite with the core columns + a serialized `fields` blob, via `SqlDialect`.)
- [x] Provide schema examples for common backends: SQLite, MySQL/MariaDB, and PostgreSQL.
  (`docs/sql-schema.md`; `SqlDialect.SQLITE` / `MYSQL` / `POSTGRES`.)
- [x] Add schema versioning and migrations for SQL-backed stores. (`SqlMigrations`: a `<table>_meta`
  version row, transactional step application, run idempotently on construction.)
- [x] Add batching support for bulk removals so `denyAll`, `cancelAllFrom`, and `clearFor` do not
  issue many separate write calls. (`InvitationStore.removeAll`; SQL batches one statement, JSON one
  rewrite; bulk paths in the manager route through it.)
- [x] Add an optional async/write-behind store wrapper for plugins that cannot block the server thread.
  (`AsyncStore`: single worker thread, ordered writes, synchronous `load`/`flush`, drains on `close`.)
- [x] Define store failure behavior for `send` and terminal actions:
  - fail the operation before mutating memory,
  - mutate memory then retry persistence,
  - or mark the manager unhealthy.
  (`StoreFailurePolicy`; `InvitationManager.isHealthy()`; `SendResult.StoreFailure` /
  `StoreWriteFailedException`.)
- [x] Make `send` state changes transactional with store writes, or add rollback if `store.save`
  fails after indexes were updated. (Under `FAIL_BEFORE_MUTATING`, a failed save rolls the index back
  out and returns `SendResult.StoreFailure`.)
- [x] Add `InvitationStore.removeAll(ids)` and possibly `replace(old, new)` for atomic policies.
  (Both on the SPI; `replace` used by the duplicate `REPLACE_EXISTING` path; SQL `replace` is a single
  transaction.)
- [x] Add duplicate/corrupt-store handling during `rehydrate()`: duplicate ids, duplicate pairs,
  malformed records, invalid UUIDs, and expired rows. (`reconcileOnLoad` + `RehydratePolicy` for the
  semantic cases; malformed/invalid records are quarantined by the store; expired rows expire inline.)
- [x] Add `rehydrate()` policy options:
  - trust store exactly,
  - drop invalid rows,
  - reject duplicates,
  - repair duplicates,
  - enforce caps on load.
  (`RehydratePolicy` with `TRUST_STORE` / `DROP_INVALID` / `REPAIR` presets and per-flag control.)
- [x] Improve `JsonFileStore` durability with `java.nio.file.Files.move`, `ATOMIC_MOVE` where
  supported, `REPLACE_EXISTING`, and explicit charset handling. (Temp-file write then atomic NIO move
  with a replacing-move fallback; all I/O is UTF-8.)
- [x] Add backup/recovery behavior for malformed JSON files instead of throwing without context.
  (Corrupt files are moved aside to `<name>.corrupt-<ts>` and the store starts empty;
  `recoverFromCorruption = false` opts back into a contextual `IOException`.)
- [ ] Consider replacing the tiny JSON codec with kotlinx.serialization or Jackson in the optional
  file-store artifact if dependency policy allows it. (Declined: keeping the core dependency-free; the
  built-in codec is retained and shared with the SQL `fields` blob via `FieldCodec`.)
- [x] Add file locking or documented single-process ownership for `JsonFileStore`. (Opt-in
  `lockFile = true` acquires an exclusive OS `FileLock` for the store's lifetime, released on `close`.)
- [x] Add store close/shutdown hooks for pooled SQL connections or async queues.
  (`InvitationStore : AutoCloseable`; `InvitationManager.shutdown()` calls `store.close()`.)

## 4. Concurrency And Correctness

- [x] Add concurrency tests that hammer `send`, duplicate sends, `accept`, `deny`, `cancel`, `clearFor`,
  `denyAll`, `cancelAllFrom`, `rehydrate`, and expiry callbacks from multiple threads. (`ConcurrencyTest`,
  with a real thread-pool `ThreadedScheduler` rather than the inline fake.)
- [x] Add invariant tests proving `byId`, `byInvited`, `byInviter`, timers, and store state never drift.
  (`ConcurrencyTest.assertConsistent` — every test ends by checking both reverse indexes agree with
  `all()` and the tracking store's id set matches memory.)
- [x] Document that `validate` is advisory because it runs before the synchronized mutation; the
  in-lock duplicate check remains authoritative. (KDoc on `send`.)
- [x] Decide whether `validate` should move inside the lock, receive a stable snapshot, or remain
  outside for handler safety. (Stays outside the lock — handlers may block on name/permission lookups;
  the authoritative duplicate/cap/cooldown checks are re-evaluated in-lock. Documented on `send`.)
- [x] Ensure handler callbacks never run while the manager lock is held. (Already true: hooks/observers
  dispatch via `Scheduler.runOnMainThread` *after* the synchronized mutation returns. Documented in the
  class-level concurrency contract.)
- [x] Decide whether callbacks should be synchronous with the operation result or always scheduled
  asynchronously. (Always scheduled on the main thread, never synchronous with the result; vetoes are
  the only inline pre-mutation hook. Documented in the class-level concurrency contract.)
- [x] Guard against scheduler execution races where `runLater` fires immediately while `send` is still
  completing. (Safe by construction — the timer body re-checks liveness under the lock and `index`
  precedes `runLater`; documented on `scheduleExpiry`.)
- [x] Add tests for already-expired invitations to ensure `onSend` is not fired after `onExpire`.
  (`ConcurrencyTest.already-expired invite never fires onSend after onExpire`.)
- [x] Add tests for `shutdown()` followed by terminal operations or a second `shutdown()`.
  (`ConcurrencyTest.shutdown then terminal ops and a second shutdown are safe`.)
- [x] Add tests for double `rehydrate()` and rehydrate-after-live-send behavior. (`ConcurrencyTest`;
  fixed a real bug where a second `rehydrate()` re-armed an expiry timer for an already-live invite.)
- [x] Add tests around `store.save` and `store.remove` throwing exceptions.
  (`ConcurrencyTest.store and hook exceptions under ISOLATE do not corrupt indexes`, plus the existing
  `PersistenceDurabilityTest` failure-policy coverage.)
- [x] Add tests around handler hooks throwing exceptions. (Same test — a hook that throws every 5th
  call leaves the indexes internally consistent under the default `ISOLATE` policy.)
- [x] Review mutable sets inside `ConcurrentHashMap`; all mutation is intended to be under lock, but
  access patterns should be documented and protected by tests. (Documented at the `index`/`unindex`
  declarations; the lock-free read paths are exercised throughout `ConcurrencyTest`.)

## 5. Platform Adapters

- [x] Add a Folia scheduler adapter using region/global scheduling semantics instead of Bukkit's
  classic scheduler. (`bukkit/FoliaScheduler`: both expiry timers and post-transition dispatch run on
  the `GlobalRegionScheduler`; `compileOnly` Paper, never referenced by core.)
- [x] Decide how invitation callbacks choose player/entity region context under Folia. (Invitations are
  cross-entity state owned by no single region, so all engine work runs on the **global** region
  scheduler; handlers that touch a specific player/entity re-dispatch onto that entity's scheduler
  explicitly. Documented in `FoliaScheduler`'s KDoc.)
- [x] Add Paper/Bukkit event firing adapter tests with MockBukkit or a small test harness.
  (`BukkitEventAdapterTest` on MockBukkit: `EventFiringVeto` fires/cancels the pre-events,
  `EventFiringObserver` fires the post-only expire/replace events, and post actions stay non-vetoable.)
- [x] Split artifacts if needed:
  - core module with no Bukkit dependency,
  - Bukkit/Paper adapter module,
  - Folia adapter module,
  - persistence adapters.
  (Declined for now: a single module with a strict package boundary already gives the same guarantee —
  every `org.bukkit`/`io.papermc` reference is confined to `bukkit/` and `examples/`; core is
  server-free. Promote to real modules only when published — see §10.)
- [x] Verify `compileOnly` dependencies are correct and no server-only classes leak into core APIs.
  (Paper is `compileOnly`; a grep proves no `org.bukkit`/`io.papermc` import exists outside `bukkit/`
  and `examples/`; the server-free demo and the bulk of tests build and run without a server.)
- [x] Add lifecycle integration examples for plugin enable, disable, player quit, server shutdown, and
  island disband/delete. (`examples/BukkitAdapterExamples.kt`: `onEnable`/`onDisable`, a `QuitListener`,
  `onServerShutdown`, `onIslandDisband` — with the reason-aware `clearAllFor` calls for each.)
- [x] Add command integration examples for common flows: invite, accept latest, accept by name, deny,
  deny all, revoke, revoke all, list pending, and stats. (`BukkitAdapterExamples.cmd*`, one per flow.)

## 6. Validation, Abuse Protection, And Permissions

- [x] Add built-in optional validation policies for common cases:
  self-invite prevention,
  offline target handling,
  target ignoring inviter,
  already-in-same-party,
  party full,
  inviter lacks permission,
  invited lacks permission,
  world/server restrictions.
  (`ValidationPolicy`: self-invite, target online, ignore list, same-party, party capacity, inviter /
  invited permission, and world/server restriction policies.)
- [x] Add configurable rate limits per inviter, per invited player, and per pair. (`RateLimiter` plus
  `Builder.rateLimits(...)`; admin sends bypass it.)
- [x] Add cooldown memory that survives restarts when backed by a durable store, or document it as
  runtime-only. (Documented runtime-only: pair cooldowns and sliding rate-limit windows live in memory
  and reset on restart.)
- [x] Add message keys or structured rejection codes instead of free-form rejection strings.
  (`RejectionReason.Code` plus stable `messageKey`.)
- [x] Add a typed rejection model so callers can localize messages safely. (`SendResult.PolicyRejected`
  carries `RejectionReason` with fallback text and placeholder args.)
- [x] Add optional permission/context object to `send`, `accept`, `deny`, and `cancel` if plugins need
  actor-aware authorization. (`ActorContext` is threaded through send and terminal operations.)
- [x] Add admin override paths that preserve audit information. (`adminCancel` / `adminClearAllFor`
  bypass vetoes, use `ADMIN_CLEARED`, and audit actor/admin context.)

## 7. Time, Expiry, And Lifecycle Semantics

- [ ] Add an explicit clock abstraction or extend `Scheduler.now()` guidance so all time decisions are
  testable and consistent.
- [ ] Add `expiresAfter(Duration)` builder/helper support.
- [ ] Decide if no-expiry invitations are allowed in production by default, or require an explicit
  opt-in.
- [ ] Add maximum expiry duration guardrails.
- [ ] Add expiry warning timers and cancellation of warning timers on terminal actions.
- [ ] Add bulk expiry handling for stores that can query expired rows directly.
- [ ] Decide whether invitations should expire during server downtime, on next startup, or only while
  the server is running. Current behavior expires on next `rehydrate()`.
- [ ] Add lifecycle states if needed: `PENDING`, `ACCEPTED`, `DENIED`, `CANCELLED`, `EXPIRED`.
  Currently terminal invitations are removed rather than retained.

## 8. Documentation And Developer Experience

- [ ] Write KDoc for every public class, method, result type, policy enum, and builder option.
- [ ] Generate Dokka API docs.
- [ ] Expand `README.md` with production usage, not just prototype explanation.
- [ ] Add copy-paste examples for Kotlin and Java.
- [ ] Add migration notes for IslandCore and SkyblockAPI:
  - data model changes,
  - command behavior changes,
  - removal of duplicated invite lists,
  - database migration strategy,
  - player quit/disband integration.
- [ ] Add an architecture decision record explaining why core has no Bukkit dependency.
- [ ] Add a compatibility matrix for Java, Kotlin, Paper, Folia, and Minecraft versions.
- [ ] Add troubleshooting docs for store corruption, duplicate invites, scheduler issues, and missed
  expiry callbacks.

## 9. Testing And Quality Gates

- [ ] Add concurrency and race tests.
- [ ] Add property-style tests for random operation sequences and index invariants.
- [ ] Add SQL store integration tests with Testcontainers or embedded SQLite.
- [ ] Add JSON file corruption and recovery tests.
- [ ] Add Java source compatibility tests that compile a small Java consumer.
- [ ] Add binary compatibility checks before stable releases.
- [ ] Add static analysis: ktlint or Spotless, Detekt if useful, and dependency checks.
- [ ] Add CI workflow for build, test, lint, docs, and publish dry-run.
- [ ] Add coverage reporting with minimum thresholds for core manager behavior.
- [ ] Add performance tests for expected pending invite counts and bulk operations.
- [ ] Add stress tests around scheduler cancellation and expiry under load.

## 10. Packaging, Versioning, And Release

- [ ] Add `maven-publish` configuration.
- [ ] Publish snapshot and release artifacts to the chosen Maven repository.
- [ ] Decide artifact coordinates and module names before adoption.
- [ ] Add semantic versioning policy.
- [ ] Add `CHANGELOG.md`.
- [ ] Add license metadata.
- [ ] Add Gradle metadata/source jar/javadoc jar publishing.
- [ ] Add dependency locking or version catalog if this joins a larger multi-module build.
- [ ] Decide whether the prototype jar should become a standalone module or be moved into NetworkAPI
  source directly.
- [ ] Remove prototype-only naming before first production release:
  version `0.1-PROTOTYPE`,
  project name,
  README wording,
  demo-only assumptions.

---

## Suggested Implementation Order

1. Stabilize public API policy: duplicate handling, receiver caps, cooldowns, typed rejection/result
   models, and `Duration` helpers.
2. Define production error semantics: store failures, handler exceptions, event cancellation, and
   callback ordering.
3. Add SQL persistence and harden `JsonFileStore`.
4. Add Bukkit events and Folia scheduling.
5. Add concurrency, failure, Java compatibility, and adapter tests.
6. Add docs, publishing, CI, and migration notes.

The two highest-risk areas are durability under failure and concurrency around lifecycle transitions.
Handle those before broad plugin adoption.
