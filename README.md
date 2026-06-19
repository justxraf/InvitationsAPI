# InvitationsAPI

A reusable, plugin-agnostic invitations engine. It runs and tests without a server, while still
including optional Bukkit and Folia adapters.

## The idea

One generic engine owns the shared invite behavior: registration, reverse lookups, de-duplication,
expiry timers, validation, persistence hooks, and lifecycle events. Each plugin supplies its own
invitation type and handler.

## Why this shape

- **No Bukkit in the core.** The core API is pure Kotlin; server integration stays in adapters.
- **Single source of truth.** The manager stores each invitation once and maintains inviter/invited
  indexes internally.
- **Centralised expiry.** One cancellable timer per invitation, handled by the engine.
- **Typed results.** Send, accept, deny, and cancel paths expose structured outcomes.

## Engine features

- De-duplication of inviter/invited pairs.
- Optional caps, cooldowns, rate limits, and validation policies.
- JSON and SQL persistence stores.
- Rehydration after restart.
- Logging, metrics, audit, and lifecycle observer hooks.
- Bukkit events, Bukkit scheduler, and Folia scheduler adapters.

## Run it

```bash
./gradlew test
./gradlew run
```

See `demo/Demo.kt` and `examples/` for usage examples.
