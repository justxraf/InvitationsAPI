# Changelog

All notable changes to InvitationsAPI are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
See [docs/versioning.md](docs/versioning.md) for the versioning and public-API stability policy.

## [Unreleased]

## [0.1.0] - 2026-06-19

First publishable release. The library graduated from a prototype: the `0.1-PROTOTYPE`
version tag and prototype-only wording were removed and the public ABI is now tracked
in `api/` by the binary-compatibility validator.

### Added

- Generic invitation model with inviter, invited, created time, and optional expiry.
- Send, accept, deny, cancel, clear, and bulk operations, with typed result models.
- Duplicate handling (reject / replace / refresh), per-inviter and per-invited caps,
  pair cooldowns, and sliding-window rate limits.
- Optional validation policies: permissions, online checks, ignore lists, party
  capacity, world/server restrictions, and self-invite prevention.
- Expiry timers, expiry-warning callbacks, and expiry guardrails (`requireExpiry`, `maxExpiry`).
- Persistence backends: in-memory, JSON file, and SQL/JDBC, plus an async write-behind wrapper.
- Rehydration after restart with configurable reconciliation of duplicate/invalid rows.
- Observability: pluggable logging, metrics, audit, generic observers, and vetoes.
- Bukkit event adapter, Bukkit scheduler, and Folia scheduler (`com.justxraf.invitations.bukkit`).
- Maven publishing (`maven:com.justxraf:invitations`) with source and Dokka-Javadoc jars,
  Apache-2.0 license metadata, and PGP signing for Maven Central.

[Unreleased]: https://github.com/justxraf/InvitationsAPI/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/justxraf/InvitationsAPI/releases/tag/v0.1.0
