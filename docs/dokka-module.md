# Module InvitationsAPI

A generic, server-free invitation engine for party invites, teleport/trade requests, duels, and
similar accept/deny flows. The core (`com.justxraf.invitations`) has no Bukkit dependency; optional
Bukkit/Folia adapters live in `com.justxraf.invitations.bukkit`.

See the project [README](https://github.com/justxraf/InvitationsAPI) for usage, the `docs/` folder
for examples, the architecture decision record, the compatibility matrix, and troubleshooting.

# Package com.justxraf.invitations

The core engine and its SPIs: [InvitationManager], the [Invitation] model, persistence
([InvitationStore] and friends), validation/abuse policies, and the observability seams (logger,
metrics, audit, observers, vetoes).

# Package com.justxraf.invitations.bukkit

Optional Bukkit/Paper and Folia adapters: schedulers, cancellable events, and the event-firing
veto/observer that bridge the core seams onto the Bukkit event bus. Compiled against Paper as
`compileOnly`; never referenced by the core.
