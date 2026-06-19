# InvitationsAPI

InvitationsAPI is a small Kotlin library for building invitation flows: party invites, teleport
requests, trade requests, duel challenges, team joins, and similar systems.

The core is server-free and can run in any JVM/Kotlin project. Bukkit and Folia support is provided
through optional adapters.

## Features

- Generic invitation model with inviter, invited, created time, and optional expiry.
- Send, accept, deny, cancel, clear, and bulk operations.
- Typed results for terminal actions and send failures.
- Duplicate handling with reject, replace, or refresh policies.
- Per-inviter and per-invited caps.
- Pair cooldowns and sliding-window rate limits.
- Optional validation policies for permissions, online checks, ignore lists, party capacity, and
  world/server restrictions.
- Expiry timers and expiry warning callbacks.
- JSON file and SQL/JDBC persistence stores.
- Rehydration after restart.
- Logging, metrics, audit, observers, and veto hooks.
- Bukkit event adapter, Bukkit scheduler, and Folia scheduler.

## Requirements

- Java 21
- Kotlin JVM 2.0.21
- Gradle wrapper included

## Build

```bash
./gradlew test
./gradlew jar
```

On Windows:

```bat
gradlew.bat test
gradlew.bat jar
```

The jar is written to `build/libs/`.

## Quick Start

```kotlin
import com.justxraf.invitations.BasicInvitation
import com.justxraf.invitations.InvitationHandler
import com.justxraf.invitations.InvitationManager
import com.justxraf.invitations.Invitations
import com.justxraf.invitations.Scheduler
import java.time.Duration
import java.util.UUID

val scheduler = object : Scheduler {
    override fun runOnMainThread(block: () -> Unit) = block()

    override fun runLater(delayMillis: Long, block: () -> Unit): Scheduler.Cancellable =
        object : Scheduler.Cancellable {
            override fun cancel() = Unit
        }
}

val handler = object : InvitationHandler<BasicInvitation> {
    override fun onSend(invitation: BasicInvitation) {
        println("Invite sent")
    }

    override fun onAccept(invitation: BasicInvitation) {
        println("Invite accepted")
    }
}

val manager = InvitationManager.builder(handler, scheduler)
    .maxPerInviter(5)
    .pairCooldownMillis(Duration.ofSeconds(30).toMillis())
    .build()

val inviterId = UUID.randomUUID()
val invitedId = UUID.randomUUID()
val invite = Invitations.between(inviterId, invitedId, ttl = Duration.ofMinutes(2))

when (val result = manager.send(invite)) {
    is InvitationManager.SendResult.Accepted -> manager.accept(result.invitationId)
    is InvitationManager.SendResult.Duplicate -> println("Already invited")
    is InvitationManager.SendResult.RateLimited -> println("Try again in ${result.retryAfterMillis}ms")
    else -> println("Invite was not sent: $result")
}
```

## Core Types

- `Invitation` is the base contract for invite data.
- `BasicInvitation` is a ready-to-use implementation.
- `InvitationHandler<T>` receives lifecycle callbacks and optional validation.
- `InvitationManager<T>` owns registration, lookup, expiry, persistence, and terminal actions.
- `Scheduler` lets the core run without depending on any server API.

## Persistence

Use `InvitationStore.InMemory()` for temporary state, `JsonFileStore` for file-backed storage, or
`SqlInvitationStore` for JDBC-backed storage.

Call `rehydrate()` after creating the manager if you want stored invitations loaded back into memory.
Call `shutdown()` when your application stops so timers and stores can close cleanly.

## Bukkit And Folia

The `bukkit` package contains:

- `BukkitScheduler`
- `FoliaScheduler`
- Bukkit invitation events
- `EventFiringVeto`
- `EventFiringObserver`

The core package does not depend on Bukkit classes.

## Examples

- `src/main/kotlin/com/justxraf/invitations/demo/Demo.kt`
- `src/main/kotlin/com/justxraf/invitations/examples/KotlinExamples.kt`
- `src/main/java/com/justxraf/invitations/examples/JavaExamples.java`
- `src/main/kotlin/com/justxraf/invitations/examples/BukkitAdapterExamples.kt`
