# InvitationsAPI

A reusable, plugin-agnostic invitations engine. Built as a standalone Gradle project so it runs and
is tested **without a server**. 
Works with Bukkit and Folia! Can be used in any Kotlin project.

## The idea

One generic engine owns everything that is *identical* across every invite system (registry,
reverse lookups, de-dup, expiry timers). Each plugin supplies its own concrete invitation type and a
handler that gives it *meaning*. Island invites, teleport requests, and any future plugin all share
the same engine.

```
NetworkAPI (root, depended on by everyone)
  └── invitations/        <-- this prototype lives here eventually
        Invitation         interface: id, inviter, invited, createdAt, expiresAt?
        InvitationHandler  interface: onSend/Accept/Deny/Cancel/Expire + validate(invite, existing)
        InvitationManager  the engine: registry + by-invited/by-inviter indexes + expiry
                           + per-inviter limit + clearFor(player)
        Scheduler          tiny abstraction (no Bukkit in the core); now()/runOnMainThread/runLater
        bukkit/
          BukkitScheduler  the ONLY server-coupled file — production adapter (compileOnly Paper)
```

## Why this shape

- **No Bukkit in the core.** `Invitation`/`InvitationHandler`/`InvitationManager` are pure Kotlin, so
  the engine is unit-testable and usable by any plugin. The server is reached only through the small
  `Scheduler` interface; `BukkitScheduler` is the single adapter you'd ship in your plugin.
- **Single source of truth.** The manager holds the invitation once and maintains the by-invited and
  by-inviter indexes itself.
- **Centralised expiry.** One cancellable timer per invitation, handled by the engine.
- **Generics flow back to the caller.** `getInvitesFor(uuid)` returns the plugin's own type
  (`IslandInvite`, `TeleportRequest`, …), never a bare `Invitation`.

## Engine features

- **De-dup** of (inviter → invited) pairs, plus an optional `maxPerInviter` cap.
- **`validate(invite, existing)`** — the handler sees the inviter's current invites, so it can
  enforce custom rules ("already invited", per-island limits) before anything is registered.
- **`clearFor(player)`** — drop every invite a player is part of (either direction) and fire
  `onCancel(..., PARTY_CLEARED)`; use on disconnect or island disband.
- **Centralised expiry**, with already-past timestamps expired inline rather than after a tick.
- **`onCancel(invite, reason)`** distinguishes a deliberate `REVOKED` from a `PARTY_CLEARED`.

## Run it

```bash
./gradlew test    # 12 unit tests: lifecycle, dedup, validate, limit, clearFor, expiry
./gradlew run     # server-free demo: island invites + teleport requests on the SAME engine
```
There is quite a few examples in `demo/Demo.kt`. I will paste some usage examples once more features are present.
