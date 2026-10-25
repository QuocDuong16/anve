# AnVề — Project Core

Monorepo for AnVề: an Android (Jetpack Compose) client backed by a Go HTTP
API and PostgreSQL/PostGIS. Modular monorepo per ADR 0001 (`docs/adr/`):
one repo, one pinned toolchain, but each app keeps its own build system and
its own CI workflow. Current phase is M0 "Foundation": health/meta endpoints
plus a splash screen only; product features land milestone by milestone.

## Source map

- `apps/server` — Go module `github.com/QuocDuong16/anve/server`.
  - `cmd/api`, `cmd/migrate` — thin entrypoints only; business logic belongs in `internal/`.
  - `internal/httpapi` — chi router. Current surface: `/healthz`, `/readyz`, `/api/v1/version`.
  - `internal/config` — env-based config with dev-friendly defaults.
- `apps/android` — app `com.anve.app`; UI under `ui/` grouped by feature (currently `splash`, `theme`).
- `packages/api-contracts/openapi.yaml` — the HTTP contract; single source of truth for the API surface.
- `infra/docker/docker-compose.yml` — local stack (postgres/postgis, one-shot `migrate`, `api`); always runs with the repo root as project directory so `.env` and relative paths work the same locally and in CI. Postgres publishes `127.0.0.1:$POSTGRES_PORT` through a dedicated, otherwise-empty `db-host` bridge network: Docker does not forward published ports for containers whose only network is `internal: true`, so without it `localhost:5432` silently refuses connections.
- `infra/migrations` — ordered SQL migrations applied by the custom migrator in `apps/server/cmd/migrate` (not golang-migrate). The migrator owns `schema_migrations` (created in `ensureBookkeepingTable`) and serializes runs with a `pg_advisory_lock`; business migrations must never create or drop the bookkeeping table — `0001_extensions.down.sql` is a deliberate no-op for that reason. Integration tests for the migrator live in `apps/server/cmd/migrate` (skipped without `TEST_DATABASE_URL`; need a PostGIS-capable server).
- `docs/adr` — accepted architecture decisions.
- `scripts/` — toolchain drift check (`check_toolchain_versions.sh`) and Dependabot schema validation (`check_dependabot.py`).

## Invariants

- Contract-first: change `packages/api-contracts/openapi.yaml` before changing handlers.
- `mise.toml` + `mise.lock` are the single source of truth for the Go release, JDK and Gradle distribution; versions that appear in more than one file are guarded by the drift check.
- The API server intentionally starts without a database (`DATABASE_URL` optional); the DB readiness check lands with the first DB-backed feature.
- `middleware.RealIP` is deliberately not used in the router (X-Forwarded-For spoofing); a trusted-proxy allowlist is a deployment-time concern.
- The dev database is published on loopback only; production deployments keep the database on an internal network with no published port.
- Android backup/transfer rules exclude the `sharedpref` and `database` domains by default; the policy for a future Room database (location history, offline queue) must be decided per file before anything may leave the device.

## Product documentation

The product scope, status taxonomy (Implemented / Verified / Planned /
Proposed / Not in scope) and milestone plan live in `docs/product/`
(`overview.md`, `features.md`, `user-journeys.md`, `privacy-and-safety.md`,
`roadmap.md`, `glossary.md`, `non-functional-requirements.md`) and
`docs/architecture/overview.md`. These are the source of truth for product
intent — check them before developing a feature, and update them when feature
scope changes. Do not assume planned functionality is implemented: everything
beyond M0 (splash screen, health/meta endpoints, migration framework) is
planned work described in documentation only. AGENTS.md has a
"Product context & source of truth" section pointing to the same documents.

## Other memories

- Read `mem:tech_stack` for frameworks, libraries and where version pins live (including the AGP 9 / Kotlin setup, which is easy to get wrong).
- Read `mem:suggested_commands` for the make-based daily workflow and tool invocation rules.
- Read `mem:conventions` for Go/Kotlin style and repo-wide rules (contract-first, migrations, commits).
- Read `mem:task_completion` for the gates a change must pass before it is done.
