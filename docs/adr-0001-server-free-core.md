# ADR 0001 — The core has no Bukkit dependency

## Context

InvitationsAPI is the shared invitation engine
Invitation flows (party invites, teleport/trade requests, duels, team joins) are mostly
platform-independent state machines: register a pending invite, de-duplicate, expire, accept/deny/cancel,
persist. The only genuinely platform-specific concerns are *threading* (which thread a callback runs on)
and *event integration* (firing/listening on the Bukkit event bus).

We had to decide whether the engine should depend on `org.bukkit` / `io.papermc` directly, or isolate
the platform behind seams.

## Decision

**The core package `com.justxraf.invitations` does not reference any Bukkit, Paper, or Folia class.**
Platform concerns are expressed through small interfaces the core owns:

- `Scheduler` — main-thread dispatch and delayed tasks (`runOnMainThread`, `runLater`, `now`).
- `InvitationObserver` / `InvitationVeto` — generic listener and pre-mutation veto seams.
- `InvitationStore` — persistence SPI.

All Bukkit/Paper/Folia code lives in the separate `com.justxraf.invitations.bukkit` package:
`BukkitScheduler`, `FoliaScheduler`, the cancellable `InvitationEvent` classes, and the
`EventFiringVeto` / `EventFiringObserver` that bridge the Bukkit event bus onto the core seams. Paper is
a **`compileOnly`** dependency — it is needed only to compile those adapters and never appears on the
runtime/core API surface.

For now this is enforced by a **package boundary**, not separate Gradle modules: a grep proves no
`org.bukkit` / `io.papermc` import exists outside `bukkit/` and `examples/`.

## Consequences

**Positive**

- The engine is unit-testable without a running server: tests inject an inline `Scheduler` and a
  controllable `Clock`, and the bulk of the suite runs server-free. Concurrency, persistence, and
  validation are tested directly.
- The same artifact can be reused outside Minecraft (proxy plugins, services, tooling).
- Folia support is just another `Scheduler` implementation; the engine needed no changes.
- Bukkit event semantics (cancellable pre-events → typed `*.Vetoed` results) are implemented once, as
  a veto/observer adapter, rather than threaded through the engine.

**Negative / costs**

- Indirection: callers must supply a `Scheduler` and, for events, register the firing adapters. The
  builder and the `bukkit/` examples keep this boilerplate to a few lines.
- A single module means the boundary is convention-enforced (grep/review) rather than compiler-enforced.
  Accepted until publishing, when the split into modules makes it structural.