# ADR 0001 — Modular monorepo foundation

- **Status:** Accepted
- **Date:** 2026-10-21

## Context

AnVề needs an Android client, a Go backend, a shared API contract and a
PostgreSQL/PostGIS database. The first release (M0 — Foundation) has to
prove that the whole toolchain works end to end before any product feature
is written: the Android app must build an APK from the command line, the Go
service must answer `/healthz`, the database must start from a single
command, and CI must verify both applications independently.

The two applications are in different languages with completely different
build systems. Sharing a repository is convenient, but a naive shared
repository also shares a single build tool, a single release cadence and a
single CI pipeline — which is exactly what M0 must avoid.

## Decision

Use a **modular monorepo**: one Git repository, one pinned toolchain, but
each application keeps its own language, build system and CI workflow.

- `apps/android` — Kotlin + Jetpack Compose, built by Gradle.
- `apps/server` — Go, built by `go build`.
- `packages/api-contracts` — the OpenAPI document, the only shared
  interface between the two applications.
- `infra/docker` and `infra/migrations` — the local stack and the database
  schema.
- `docs/adr` — the decisions themselves.

The toolchain is pinned in `mise.toml` and resolved in `mise.lock`, so a
developer machine and a CI runner install byte-identical tools.
`scripts/check_toolchain_versions.sh` fails the build when a version that
appears in two places disagrees — the Gradle version, for example, exists
both in `mise.toml` and in the Gradle wrapper properties.

CI runs two independent workflows, `go.yml` and `android.yml`, so a
failing Android build does not hide a failing Go build and vice versa.

The Android SDK itself is deliberately *not* managed by mise: it is a large
system-wide component that CI installs through `android-actions/setup-android`
and that developers keep in `$ANDROID_HOME`.

## Consequences

**Good**

- One clone, one `make help`, one pinned toolchain.
- The API contract is a first-class artifact in the repository, so the two
  applications cannot drift apart silently.
- The two applications are built and tested independently in CI, and neither
  blocks the other.

**Bad**

- The repository needs explicit conventions to stop the applications from
  growing into each other's directories. `AGENTS.md` and the Makefile
  targets are that convention.
- Toolchain pins appear in more than one file, which is why the drift
  check exists at all.

**Neutral**

- A single repository means a single branch protection policy. When the
  applications release on different cadences, release workflows have to be
  added per application — M0 does not need this yet.
