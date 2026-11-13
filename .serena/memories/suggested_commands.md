# Suggested Commands

Everything goes through the Makefile from the repo root; `make help` is the default goal and lists all targets. CI runs the same targets.

## One-time setup

- `mise install` — install the pinned toolchain (mise itself must be installed first).
- `make toolchain-check` — verify the version pins agree across files.
- `cp .env.example .env` — local configuration; the Makefile loads and exports it.

## Daily workflow

- `make docker-up-dev` (alias `up-dev`) — build & start local PostgreSQL/PostGIS + API; API on `http://localhost:8080`, `GET /healthz`.
- `make docker-down-dev` / `docker-logs-dev` (aliases `down-dev` / `logs-dev`); `make docker-clean-dev` also deletes only development volumes.
- Production: copy `.env.prod.example` to private `.env.prod`, configure DNS/OAuth/secrets, then `make docker-config` and `make docker-up`; `make docker-down` preserves production volumes.
- `make migrate-up-dev` / `migrate-down-dev` — apply pending migrations / roll back the last development migration (runs the migrator inside Docker; no local DB access needed). Production migrations run as part of `make docker-up`.
- `make server-run` — run the API locally via `go run ./cmd/api` from `apps/server` (works without a DB; uses `.env` values).
- `make android-build` — debug APK at `apps/android/app/build/outputs/apk/debug/app-debug.apk` (requires `ANDROID_HOME`).
- `make android-test`, `make android-lint` — JVM unit tests / Android lint.

## Go checks (run from repo root via make)

- `make server-fix` — `go mod tidy` + `go fmt`.
- `make server-test` / `make server-test-race`.
- `make server-check` — full Go gate: gofmt clean, `go mod tidy -diff`, vet, golangci-lint, govulncheck, tests, build of `cmd/api` + `cmd/migrate`.
- `make server-test-integration` — migrator integration tests against the running local stack (`make docker-up-dev` first); skipped without `TEST_DATABASE_URL`.

## Android checks

- `make android-check` — `lintDebug` + `testDebugUnitTest` + `assembleDebug`.
- `make android-clean`; `make clean` also removes `apps/server/bin`.

## Contracts & dependency config

- `make contracts-lint` — validate `packages/api-contracts/openapi.yaml` parses (needs python3 + PyYAML).
- `make deps-lint` — validate `.github/dependabot.yml` against the bundled schema.

## Outside make

- Go tools must run through mise to get pinned versions, e.g. `mise exec -- golangci-lint run ./...` from `apps/server`.
- Migrator binary directly: `migrate up|down`, needs `DATABASE_URL`, `MIGRATIONS_DIR` (default `migrations`).
