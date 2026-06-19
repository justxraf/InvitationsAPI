# Troubleshooting

Common production issues and how the API is designed to handle them.

## Store corruption

**Symptom:** the JSON store file is malformed (truncated by a crash, hand-edited, disk error) and the
plugin fails to load invites, or you see a `<name>.corrupt-<timestamp>` file appear.

- By default `JsonFileStore` **quarantines** a corrupt file: it is moved aside to
  `<name>.corrupt-<timestamp>` and the store starts empty rather than throwing. The bad file is kept so
  you can inspect/recover it manually.
- To fail loudly instead (e.g. so an operator must intervene), construct it with
  `recoverFromCorruption = false`; load then throws a contextual `IOException`.
- Writes are crash-resistant: every change is written to a temp file and moved into place with an
  atomic NIO move (falling back to a replacing move where atomic moves are unsupported). A crash
  mid-write should leave the previous good file intact.
- To prevent two processes writing the same file (the usual root cause), enable the exclusive lock:
  `JsonFileStore(file, serializer, lockFile = true)`. A second owner fails fast with an
  `IllegalStateException`.
- For SQL stores, schema drift is handled by `SqlMigrations` (a `<table>_meta` version row, applied
  transactionally on construction). If migration fails, the transaction rolls back and the constructor
  throws — check the DB user has DDL permission and the `table` name is a valid identifier.

## Duplicate invites

**Symptom:** the same pair appears to have more than one pending invite, or a re-send is unexpectedly
rejected/accepted.

- Choose the right `DuplicatePolicy` on the builder:
  - `REJECT_EXISTING` (default) → a second send returns `SendResult.Duplicate(existingId)`.
  - `REPLACE_EXISTING` → the old invite is cancelled (`DUPLICATE_REPLACED`) and replaced; you get
    `SendResult.Replaced`.
  - `REFRESH_EXPIRY` → the existing invite's expiry is extended; you get `SendResult.Refreshed`.
- The duplicate check is **authoritative under the manager lock** — `InvitationHandler.validate` runs
  before the lock and is advisory only, so don't rely on it to prevent duplicates.
- After a restart, duplicate **rows** in the store are reconciled by the `RehydratePolicy`:
  `REPAIR` (default) drops duplicate ids/pairs and deletes them from the store; `DROP_INVALID` drops
  them from memory only; `TRUST_STORE` keeps everything as-is. If you see duplicates only after a
  restart, your policy is likely `TRUST_STORE`.
- If duplicates persist with `REPAIR`, confirm two processes aren't writing the same store (see the
  file lock above) — the manager can only reconcile what one process owns.

## Scheduler issues

**Symptom:** callbacks run on the wrong thread, expiry never fires, or you see region/thread errors on
Folia.

- Use the scheduler that matches the server: `BukkitScheduler(plugin)` on Spigot/Paper,
  `FoliaScheduler(plugin)` on Folia. Mixing them up is the most common cause of thread/region errors.
- All lifecycle callbacks (`onSend`/`onAccept`/…) and observers are dispatched via
  `Scheduler.runOnMainThread` **after** the state mutation, and **never** while the manager lock is
  held. Only vetoes run inline before mutation. If a callback touches a specific player/entity on Folia,
  re-dispatch onto that entity's scheduler yourself — engine work runs on the global region.
- If you implement `Scheduler` yourself, make sure `runLater` actually schedules into the future and
  returns a working `Cancellable`; an inline fake that runs immediately is fine for tests but will make
  every invite "expire" or fire instantly in production.
- Engine time comes from a single `Clock`, defaulted to the scheduler. If timers fire at the wrong time
  in tests, you probably injected a fixed `Clock` that disagrees with the scheduler's `now()`.

## Missed expiry callbacks

**Symptom:** invites that should have expired are still pending, or `onExpire` didn't fire.

- Expiry is enforced by an in-process timer, so an invite that lapses **while the server is down** has
  no timer to fire. This is expected. On startup, call `rehydrate()` (re-arms timers and expires
  anything already past) and then `sweepExpired()` (bulk-expires lapsed rows, using the store's
  targeted `loadExpired` query where available). Optionally run `sweepExpired()` on an interval as a
  backstop for a flaky scheduler.
- Invites with `expiresAt == null` **never expire** by design. To forbid permanent invites, set
  `requireExpiry(true)` — sends without an expiry then return `SendResult.ExpiryRequired`.
- Expiry-**warning** callbacks (`onExpiryWarning`) only fire for offsets configured via
  `expiryWarningOffsetsMillis(...)`, and are best-effort: if the invite is already gone when a warning
  timer fires, nothing is warned. Warning timers are cancelled together with the expiry timer on any
  terminal action.
- If `onExpire` throws and you've set `LifecycleErrorPolicy.PROPAGATE`, the exception surfaces to the
  timer thread. Under the default `ISOLATE`, it is logged and routed to your `errorCallback` instead —
  check there if expirations seem to "vanish".

## Store write failures

**Symptom:** `send` returns `SendResult.StoreFailure`, or the manager reports unhealthy.

- The behavior is governed by `StoreFailurePolicy`:
  - `FAIL_BEFORE_MUTATING` (default) → a failed save rolls the in-memory indexes back and returns
    `SendResult.StoreFailure`; memory never drifts from disk.
  - `MUTATE_THEN_RETRY` → memory is updated and the write retried (`storeWriteRetries`); favours
    availability over strict durability.
  - `MARK_UNHEALTHY` → the manager flips `isHealthy()` to `false` on the first failure; gate traffic on
    it and alert.
- Every store failure increments the metrics store-failure counter and is logged via your
  `InvitationLogger`. Wrapping the store in `AsyncStore` moves writes off the main thread; its write
  errors go to the `onError` callback you pass it, not back to the `send` caller.

## Getting more signal

- Wire an `InvitationLogger` (e.g. `InvitationLogger.fromJul(plugin.logger)`) and an
  `InvitationMetrics.InMemory()` on the builder; `metrics.snapshot()` exposes per-outcome counters.
- Add an `InvitationAudit` sink to capture who invited whom, the outcome, reason, and timestamps for
  after-the-fact debugging.
- Generate the API reference with `./gradlew dokkaHtml` and open `build/dokka/html/index.html`.
