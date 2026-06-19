# Versioning and release policy

InvitationsAPI follows [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html):
`MAJOR.MINOR.PATCH`.

## What the public API is

The public API is the binary surface tracked by the
[binary-compatibility validator](https://github.com/Kotlin/binary-compatibility-validator),
dumped under [`api/`](../api). The `demo` and `examples` packages are **not** part of the public
API and may change at any time (they are excluded from the ABI dump and from publishing).

## Version increments

- **MAJOR** — incompatible public-API changes: removing or renaming public types/members,
  changing signatures or semantics in a way that breaks existing callers, or changing the
  on-disk/SQL persistence format without a migration.
- **MINOR** — backward-compatible additions: new public types/members, new optional behavior,
  new persistence backends or adapters. Existing callers keep compiling and behaving.
- **PATCH** — backward-compatible bug fixes and internal changes with no public-API impact.

A public-API change is intentional only after running `./gradlew apiDump` and committing the
updated `api/` dump alongside the change. CI fails on any undeclared ABI change.

## Pre-1.0

While the version is `0.x`, the API is still stabilizing: a MINOR bump (`0.x → 0.(x+1)`) may
include breaking changes, which will always be called out in [CHANGELOG.md](../CHANGELOG.md).
Once the API is considered stable the project releases `1.0.0` and the rules above apply strictly.

## Snapshots vs releases

- `…-SNAPSHOT` versions publish to the Sonatype snapshots repository and are not signed.
- Bare versions (e.g. `0.1.0`) are release builds: PGP-signed and published to Maven Central.

## Release checklist

1. Move `## [Unreleased]` items into a new dated `## [x.y.z]` section in `CHANGELOG.md`.
2. Set `version` in `build.gradle.kts` to `x.y.z` (no `-SNAPSHOT`).
3. `./gradlew clean check apiCheck` — tests, static analysis, and ABI gate must pass.
4. `./gradlew publish` with signing + OSSRH credentials configured (see below), then release
   the staging repository on Sonatype.
5. Tag the release: `git tag vx.y.z && git push origin vx.y.z`.
6. Bump `version` to the next `-SNAPSHOT` and add a fresh `## [Unreleased]` heading.

## Required credentials (never committed)

Provided as Gradle properties or `ORG_GRADLE_PROJECT_*` environment variables:

| Property          | Purpose                                  |
| ----------------- | ---------------------------------------- |
| `ossrhUsername`   | Sonatype Central user token name         |
| `ossrhPassword`   | Sonatype Central user token secret       |
| `signingKey`      | ASCII-armored PGP private key (in-memory) |
| `signingPassword` | Passphrase for that key                  |

For local consumption, `./gradlew publishToMavenLocal` needs none of these.
