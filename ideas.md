# InvitationsAPI Ideas

## Progressive abuse protection for repeated invites

### Problem

Some invitation flows can be used to annoy a specific user without technically violating a simple
short cooldown. For example, an inviter can send an invite, wait a minute, send again after the
target declines, and repeat this enough that the target effectively receives spam.

The current API already has useful building blocks:

- `pairCooldownMillis(...)` blocks a pair briefly after duplicate or terminal actions.
- `RateLimiter` supports runtime-only sliding windows per inviter, per invited user, and per pair.
- `ActorContext` can identify the user who triggered an action, even when the invitation pair itself
  uses a logical endpoint rather than the human actor.
- `InvitationAudit` can observe terminal outcomes.

Those primitives do not yet provide durable, escalating abuse protection. In particular, they do not
remember decline history across restarts, increase penalties for a pair, or block a sender from
repeatedly contacting the same target.

### Goal

Add an optional API-level abuse policy that can:

- Cap repeated sends to the same target over longer windows, such as `3 invites per hour`.
- Escalate from normal cooldowns to a longer cooldown, such as `30 minutes`, after repeated sends or
  repeated declines.
- Automatically block future invitations from one sender to one target when the target repeatedly
  declines that sender's invites.
- Persist abuse state so penalties survive restarts.
- Let host plugins decide whether the policy key is the invitation pair, the actor pair, or a custom
  logical key.

### Proposed shape

Introduce a durable policy layer near `RateLimiter`, but broader than rate limiting:

```kotlin
interface InvitationAbusePolicy<T : Invitation> {
    fun beforeSend(invitation: T, actor: ActorContext?): AbuseDecision
    fun afterSend(invitation: T, actor: ActorContext?)
    fun afterDeny(invitation: T, actor: ActorContext?)
    fun afterAccept(invitation: T, actor: ActorContext?)
    fun afterCancel(invitation: T, actor: ActorContext?)
}

sealed interface AbuseDecision {
    data object Allowed : AbuseDecision
    data class Cooldown(val retryAfterMillis: Long, val level: Int) : AbuseDecision
    data class Blocked(val reason: RejectionReason? = null) : AbuseDecision
}
```

The manager would call `beforeSend` before mutating invite state. Terminal actions would notify the
policy after the manager has resolved the invitation. A blocked send should return a structured
`SendResult`, either a new `SendResult.AbuseBlocked` or a `PolicyRejected` reason reserved for abuse
protection.

### Persistent store

The policy needs a store separate from pending invitations because the core invitation store only
contains active `PENDING` invitations.

Suggested state per key:

- `senderKey`
- `targetKey`
- `sendTimestamps`
- `denyTimestamps`
- `currentPenaltyLevel`
- `cooldownUntil`
- `blockedUntil`, nullable for permanent block
- `lastUpdatedAt`

The API can provide an in-memory store for tests and lightweight users, plus a SQL-backed store for
production users.

### Keying and ActorContext

This should not always use `inviterId -> invitedId`.

Example from IslandCore:

- Island-originated invites use `inviterId = islandAddress(islandId)`.
- The actual player who clicked invite is stored separately as `originalInvitorId`.
- If abuse protection keys only by `inviterId`, one eager island member can cause the whole island to
  be penalized against that target.

The API should allow a configurable key extractor:

```kotlin
fun interface AbuseKeySelector<T : Invitation> {
    fun key(invitation: T, actor: ActorContext?): AbuseKey
}

data class AbuseKey(
    val senderKey: String,
    val targetKey: String,
)
```

Default behavior can use `invitation.inviterId` and `invitation.invitedId`. Plugins that have a real
human actor can use `ActorContext.actorId`, custom invitation fields, or metadata.

### Example escalation policy

A reasonable default policy could be:

- Allow up to `3` sends per pair per rolling hour.
- If the target denies `3` invites from the same sender within `24 hours`, enforce a `30 minute`
  cooldown.
- If the target denies `5` invites from the same sender within `7 days`, block that sender-target
  pair.
- Reset or soften penalty level after a long quiet period, for example `30 days`.
- Clear send/deny counters after an accept, because the target has chosen to engage.

The exact numbers should be configurable.

### Open questions

- Should auto-blocks be permanent by default, or expire after a configurable duration?
- Should target users be able to manually clear auto-blocks through host plugin UI?
- Should an admin actor bypass abuse protection the same way `ActorContext.admin` bypasses rate
  limiting?
- Should `RateLimiter` remain runtime-only and separate, or should the new abuse policy replace the
  per-pair long-window use case?
- Should denial count only explicit user declines, or also expiry/no-response?

