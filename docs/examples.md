# Examples

Copy-paste snippets for every primary operation, in Kotlin and Java. These mirror the compiled
examples in `src/main/kotlin/com/justxraf/invitations/examples/KotlinExamples.kt` and
`src/main/java/com/justxraf/invitations/examples/JavaExamples.java` (the Java file doubles as a
source-compatibility check), so they stay in step with the real API.

> In a plugin, replace the inline `Scheduler` with `BukkitScheduler(plugin)` or `FoliaScheduler(plugin)`
> from the `bukkit` package. See [Bukkit & Folia](#bukkit--folia) below.

## Building a manager

### Kotlin

```kotlin
import com.justxraf.invitations.*
import java.time.Duration

val handler = object : InvitationHandler<BasicInvitation> {
    override fun onSend(invitation: BasicInvitation) { /* message both parties */ }
    override fun onAccept(invitation: BasicInvitation) { /* apply the effect */ }
    override fun onCancel(invitation: BasicInvitation, reason: CancelReason) { /* log reason */ }
    override fun onExpiryWarning(invitation: BasicInvitation, remainingMillis: Long) { /* warn */ }
}

val manager = InvitationManager.builder(handler, scheduler)
    .maxPerInviter(5)
    .maxPerInvited(10)
    .pairCooldownMillis(Duration.ofSeconds(30).toMillis())
    .duplicatePolicy(DuplicatePolicy.REPLACE_EXISTING)
    .selfInvitePolicy(SelfInvitePolicy.REJECT)
    .expiryWarningOffsetsMillis(10_000L, 5_000L)
    .build()
```

### Java

```java
import com.justxraf.invitations.*;
import java.time.Duration;

final class Handler extends JavaInvitationHandler<BasicInvitation> {
    @Override public void onSend(BasicInvitation invitation) { /* message both parties */ }
    @Override public void onAccept(BasicInvitation invitation) { /* apply the effect */ }
    @Override public void onCancel(BasicInvitation invitation, CancelReason reason) { /* log reason */ }
}

InvitationManager<BasicInvitation> manager =
    InvitationManager.<BasicInvitation>builder(new Handler(), scheduler)
        .maxPerInviter(5)
        .duplicatePolicy(DuplicatePolicy.REPLACE_EXISTING)
        .selfInvitePolicy(SelfInvitePolicy.REJECT)
        .build();
```

## Sending

### Kotlin

```kotlin
val invite = Invitations.between(inviterId, invitedId, ttl = Duration.ofMinutes(2))
when (val result = manager.send(invite)) {
    is InvitationManager.SendResult.Accepted    -> result.invitationId
    is InvitationManager.SendResult.Duplicate   -> result.existingId
    is InvitationManager.SendResult.LimitReached -> result.limit
    is InvitationManager.SendResult.CooldownActive -> result.remainingMillis
    InvitationManager.SendResult.SelfInvite     -> Unit
    else -> Unit
}
```

### Java

```java
BasicInvitation invite = Invitations.between(inviter, invited, Duration.ofMinutes(2));
InvitationManager.SendResult result = manager.send(invite);
if (result instanceof InvitationManager.SendResult.Accepted accepted) {
    UUID id = accepted.getInvitationId();
} else if (result instanceof InvitationManager.SendResult.Duplicate duplicate) {
    UUID existing = duplicate.getExistingId();
} else if (result.equals(InvitationManager.SendResult.SelfInvite.INSTANCE)) {
    // self-invite rejected
}
```

## Accept / deny / cancel

Each operation has a boolean overload and a `*Detailed` variant that distinguishes not-found from vetoed.

### Kotlin

```kotlin
manager.accept(invitationId)              // by id
manager.accept(inviterId, invitedId)      // by pair
manager.deny(invitationId)
manager.cancel(invitationId)

when (val r = manager.acceptDetailed(invitationId)) {
    is AcceptResult.Accepted -> r.invitationId
    AcceptResult.NotFound    -> Unit
    AcceptResult.Vetoed      -> Unit
}
```

### Java

```java
boolean ok = manager.accept(invitationId);
manager.accept(inviter, invited);
manager.deny(id);
manager.cancel(id);

AcceptResult r = manager.acceptDetailed(invitationId);
if (r instanceof AcceptResult.Accepted accepted) {
    UUID id = accepted.getInvitationId();
} else if (r.equals(AcceptResult.NotFound.INSTANCE)) {
    // nothing matched
}
```

## Bulk and clear

```kotlin
manager.denyAll(invitedId)                                  // deny everything addressed to a player
manager.cancelAllFrom(inviterId)                            // revoke everything a player sent
manager.clearAllFor(playerId, CancelReason.PLAYER_QUIT)     // both directions, with a reason
manager.clearAsInviter(playerId, CancelReason.ADMIN_CLEARED)
manager.clearAsInvited(playerId)
```

```java
manager.denyAll(player);
manager.cancelAllFrom(player);
manager.clearAllFor(player, CancelReason.PLAYER_QUIT);
```

## Queries and counts

```kotlin
manager.getInvitesFor(invitedId, SortOrder.NEWEST_FIRST)
manager.getInvitesFrom(inviterId, SortOrder.OLDEST_FIRST)
manager.getMostRecentFor(invitedId)
manager.getInvite(inviterId, invitedId)
manager.getInvitesBetween(inviterId, invitedId)
manager.all(SortOrder.NEWEST_FIRST)
manager.countFor(invitedId); manager.countFrom(inviterId); manager.pendingCount()
```

```java
List<? extends BasicInvitation> forInvited = manager.getInvitesFor(invited, SortOrder.NEWEST_FIRST);
BasicInvitation newest = manager.getMostRecentFor(invited);
int pending = manager.pendingCount();
```

## Lifecycle

```kotlin
val stillPending = manager.rehydrate()  // on startup: load store, re-arm timers
manager.sweepExpired()                  // expire invites that lapsed during downtime
// ... server runs ...
manager.shutdown()                      // on disable: cancel timers, close store
```

```java
int stillPending = manager.rehydrate();
manager.shutdown();
```

## Persistence

```kotlin
// JSON file with an exclusive single-process lock
val store = JsonFileStore(File(dataFolder, "invites.json"), serializer, lockFile = true)

// SQL (SQLite/MySQL/PostgreSQL) — schema is migrated on construction
val sql = SqlInvitationStore({ dataSource.connection }, serializer, SqlDialect.POSTGRES)

// Wrap any store to write off the main thread
val async = AsyncStore(sql)

val manager = InvitationManager.builder(handler, scheduler)
    .store(async)
    .storeFailurePolicy(StoreFailurePolicy.FAIL_BEFORE_MUTATING)
    .build()
```

`serializer` is your `InvitationSerializer<T>`, which flattens an invitation to a `Map<String, String>`
and back. See [sql-schema.md](sql-schema.md) for the table definitions.

## Validation and rate limits

```kotlin
val manager = InvitationManager.builder(handler, scheduler)
    .rateLimits(
        perInviter = RateLimiter.Limit(max = 5, windowMillis = 60_000),
        perPair    = RateLimiter.Limit(max = 1, windowMillis = 10_000),
    )
    .validationPolicy(ValidationPolicy.selfInvite())
    .validationPolicy(ValidationPolicy.targetOnline(isOnline = presence))
    .validationPolicy(ValidationPolicy.notAlreadyInSameParty(membership))
    .validationPolicy(ValidationPolicy.partyNotFull(capacity))
    .build()
// A rejected send returns SendResult.PolicyRejected(reason); reason.messageKey localizes the message.
```

## Observability

```kotlin
val metrics = InvitationMetrics.InMemory()
InvitationManager.builder(handler, scheduler)
    .logger(InvitationLogger.fromJul(plugin.logger))
    .metrics(metrics)
    .audit { entry -> /* persist entry */ }
    .observer { event -> /* react to any LifecycleEvent */ }
    .errorPolicy(LifecycleErrorPolicy.ISOLATE)
    .errorCallback { invitation, action, throwable -> /* report */ }
    .build()
// metrics.snapshot() gives sent/accepted/denied/cancelled/expired/duplicate/rejected/… counters
```

## Bukkit & Folia

```kotlin
import com.justxraf.invitations.bukkit.*

val manager = InvitationManager.builder(handler, FoliaScheduler(plugin)) // or BukkitScheduler(plugin)
    .veto(EventFiringVeto())          // cancellable pre-events -> SendResult.Vetoed etc.
    .observer(EventFiringObserver())  // post-only InvitationExpireEvent / InvitationReplaceEvent
    .build()
```

Listen for the events like any Bukkit event:

```kotlin
@EventHandler
fun onInviteSend(event: InvitationSendEvent) {
    if (shouldBlock(event.invitation)) event.isCancelled = true
}
```

See `examples/BukkitAdapterExamples.kt` for full plugin enable/disable, player-quit, server-shutdown,
island-disband, and command-flow integration.
