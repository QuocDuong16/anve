# AnVề — Project Core

Monorepo for AnVề: an Android (Jetpack Compose) client backed by a Go HTTP
API and PostgreSQL/PostGIS. Modular monorepo per ADR 0001 (`docs/adr/`):
one repo, one pinned toolchain, but each app keeps its own build system and
its own CI workflow. Phase: M0–M2 implemented (auth/device/consent + Family Circle); M3+
planned. Engineering session state lives in `docs/implementation/progress.md`
— read it first when resuming work.

## Source map

- `apps/server` — Go module `github.com/QuocDuong16/anve/server`.
  - `cmd/api`, `cmd/migrate` — thin entrypoints only; business logic belongs in `internal/`.
  - `internal/httpapi` — chi router. Surface: health/meta + M1 auth/me/devices routes; `RequireAuth` middleware verifies sessions per request; business routes 503 without a database.
  - `internal/auth` — M1 domain: opaque sessions (SHA-256 hashes only), Google ID-token verification (`google.golang.org/api/idtoken`), PG store with transactional audit; `internal/auth/authtest` holds the in-memory store fake shared by tests.
  - `internal/audit` — privacy event recorder; `audit_log` has NO FK to users by design (survives account deletion).
  - `internal/circles` — M2 domain: circles/memberships/invites; authorization enforced AT THE STORE (membership checked before any read: outsider→404, member-without-owner-role→403); one-circle-per-user unique index is the MVP invariant; invite redeem is atomic (FOR UPDATE, single winner); owner leaving a populated circle requires dissolve=true (cascades).
  - `internal/config` — env-based config. `LISTEN_ADDR` defaults to `127.0.0.1:<PORT>` in development and is mandatory in every other environment (fail closed). Refuses `AUTH_DEV_LOGIN_ENABLED` unless `ENVIRONMENT` is exactly `development`; a non-loopback listener additionally needs `AUTH_DEV_LOGIN_ALLOW_NETWORK=true` plus a non-catch-all `AUTH_DEV_LOGIN_ALLOWED_CIDRS`.
  - `internal/httpapi` also installs `RequireDevNetwork` on the dev-login route: it checks `RemoteAddr` against the configured prefixes and never trusts `X-Forwarded-For`/`X-Real-IP`. An empty allowlist denies every request; a blocked source gets 403 `DEV_LOGIN_NETWORK_DENIED`.
- `apps/android` — app `com.anve.app`; `data/` (api Retrofit+Moshi, auth token storage) + `ui/` grouped by feature (`splash`, `signin`, `home`, `theme`); `AppContainer` is the manual DI root.
- `packages/api-contracts/openapi.yaml` — the HTTP contract; single source of truth for the API surface.
- `infra/docker/docker-compose.yml` — local stack (postgres/postgis, one-shot `migrate`, `api`); always runs with the repo root as project directory so `.env` and relative paths work the same locally and in CI. EVERY published port is loopback-only: postgres `127.0.0.1:$POSTGRES_PORT` and API `127.0.0.1:$API_PORT`. The API container sets `LISTEN_ADDR=0.0.0.0:8080` (it must accept the port mapping) and the `edge` bridge is pinned to `172.28.0.0/24` so the dev-login source allowlist can name it. Postgres uses a dedicated, otherwise-empty `db-host` bridge network: Docker does not forward published ports for containers whose only network is `internal: true`, so without it `localhost:5432` silently refuses connections.
- `infra/migrations` — ordered SQL migrations applied by the custom migrator in `apps/server/cmd/migrate` (not golang-migrate). The migrator owns `schema_migrations` (created in `ensureBookkeepingTable`) and serializes runs with a `pg_advisory_lock`; business migrations must never create or drop the bookkeeping table — `0001_extensions.down.sql` is a deliberate no-op for that reason. Integration tests for the migrator live in `apps/server/cmd/migrate` (skipped without `TEST_DATABASE_URL`; need a PostGIS-capable server).
- `docs/adr` — accepted architecture decisions.
- `scripts/` — toolchain drift check (`check_toolchain_versions.sh`), Dependabot schema validation (`check_dependabot.py`) and Compose exposure validation (`check_compose_ports.py`, Make target `compose-lint`, also a CI repository job): fails if any Compose port is published beyond loopback.

## Invariants

- Contract-first: change `packages/api-contracts/openapi.yaml` before changing handlers.
- `mise.toml` + `mise.lock` are the single source of truth for the Go release, JDK and Gradle distribution; versions that appear in more than one file are guarded by the drift check.
- The API server still starts without a database (`DATABASE_URL` optional), but since M1 `readyz` fails and business routes answer a structured 503 in that state.
- Auth invariants (M1, ADR 0002; hardened in ADR 0004): sessions are opaque tokens stored as SHA-256 hashes and verified against the DB on EVERY request (immediate revocation); one active session per device (re-login supersedes); refresh rotates BOTH tokens atomically (FOR UPDATE, single winner); `users.location_sharing_enabled` defaults to FALSE and is the consent switch every location feature must respect; consent changes commit in the same transaction as their audit row; the dev login (`POST /api/v1/auth/dev-login`) is mounted only when `ENVIRONMENT=development` and never mounted otherwise. It is also network-restricted (ADR 0005): loopback listener by default; non-loopback needs explicit opt-in + CIDR allowlist; the route checks the real source address and denies with 403 `DEV_LOGIN_NETWORK_DENIED`; catch-all CIDRs refuse startup; no shared secret is embedded in the app/repo. Dev and Google identities are ISOLATED: neither sign-in path may enter the other's email-matched account (409 `IDENTITY_CONFLICT`).
- Audit ownership (ADR 0004): every business mutation records exactly ONE audit row from inside its transaction (stores own it; services carry no recorder; no best-effort path). Failed/refused operations write no audit row.
- Account deletion (ADR 0004, amended by ADR 0005 §4): `DELETE /api/v1/me` must refuse with 409 `CIRCLE_HAS_MEMBERS` when the caller owns a circle with other members; only explicit dissolve ends a populated circle. Empty owned circles are removed in the same transaction with `circle.dissolved` (detail `via=account_deletion`). Deletion locks the USER row `FOR UPDATE` FIRST, then owned circles: every circle creation holds a FK `KEY SHARE` on that user row, so creation either commits first (and the scan/count sees it) or fails its FK after deletion. This closes the race where a newly created circle was cascaded before any member count/audit. Deletion retries on PG `40P01`/`40001`; `LeaveCircle`/`RedeemInvite` retry too. `internal/auth/export_test.go` exposes a test-only barrier after the user lock; the concurrency tests use observed `pg_stat_activity` lock waits instead of sleeps.
- Android session lifecycle (ADR 0004/0005): navigation follows the session storage flow only — a failed `/me` is "offline", never "signed out". Deletion clears local storage only after a 2xx; HTTP failure keeps the session; transport failure is an unknown outcome reconciled by an authenticated read (401 + failed refresh clears it). Sign-out always clears locally but reports `SignOutOutcome`; undelivered revocations go into a **bounded, Keystore-sealed queue** (`pending_revocations`, one JSON array sealed as a whole, max 8 records, 7-day expiry per record, random local id, no account PII) that is never usable as a session. `retryPendingRevocations()` (app start) processes records independently: refresh, persist the rotated token atomically before revoking, drop on 401, reschedule transport/5xx with 30s→6h backoff. The legacy single `pending_revocation` key is migrated on read. Offline sign-out publishes an app-scoped `SessionNotice` BEFORE clearing storage; the Sign-In screen shows a dismissible card and a successful sign-in clears it (message is transient, not persisted across process death).
- Android session storage is DataStore sealed with an Android Keystore AES-GCM key (never plaintext); the DataStore file also holds the pending revocation. `SessionInterceptor` does one rotation-aware refresh-retry on 401; Google client id lives in gitignored `values/config_local.xml` (placeholder in `values/config.xml`); debug builds default `API_BASE_URL` to `http://10.0.2.2:8080` (cleartext allowlist for 10.0.2.2/localhost only). Retrofit rejects `@Body` on `@DELETE`, so `deleteMe` uses `@HTTP(method="DELETE", hasBody=true)` (latent runtime bug found in the hardening pass).
- Device identity is an APP INSTALLATION identity: stable across restarts/updates/sign-out, new after reinstall/factory reset; no hardware identifier is read. The server keys `devices` on (user, client_device_id) and treats it accordingly.
- `middleware.RealIP` is deliberately not used in the router (X-Forwarded-For spoofing); a trusted-proxy allowlist is a deployment-time concern.
- The dev database is published on loopback only; production deployments keep the database on an internal network with no published port.
- Android backup/transfer rules exclude `sharedpref`, `database` AND `file/datastore` (Preferences DataStore lives in files/datastore, not sharedpref — this is where the sealed session blob and installation id are) in both `backup_rules.xml` (API < 31) and `data_extraction_rules.xml` (API 31+, cloud backup and device transfer). The per-file policy for a future Room database (location history, offline queue) is still open.

## Product documentation

The product scope, status taxonomy (Implemented / Verified / Planned /
Proposed / Not in scope) and milestone plan live in `docs/product/`
(`overview.md`, `features.md`, `user-journeys.md`, `privacy-and-safety.md`,
`roadmap.md`, `glossary.md`, `non-functional-requirements.md`) and
`docs/architecture/overview.md`. These are the source of truth for product
intent — check them before developing a feature, and update them when feature
scope changes. Do not assume planned functionality is implemented: M0–M2 are implemented
(automated-verified, pending on-device field validation); M3 and beyond are
planned work described in documentation only. AGENTS.md has a
"Product context & source of truth" section pointing to the same documents.

## Other memories

- Read `mem:tech_stack` for frameworks, libraries and where version pins live (including the AGP 9 / Kotlin setup, which is easy to get wrong).
- Read `mem:suggested_commands` for the make-based daily workflow and tool invocation rules.
- Read `mem:conventions` for Go/Kotlin style and repo-wide rules (contract-first, migrations, commits).
- Read `mem:task_completion` for the gates a change must pass before it is done.
