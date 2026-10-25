# Task Completion

A change is done when `make quality-check` passes and the relevant CI workflow is green. `quality-check` = toolchain-check + server-check + android-check + contracts-lint + deps-lint.

## Scoped gates (pick by what you touched)

- Go-only change: `make server-check` — gofmt clean, `go mod tidy -diff`, `go vet`, golangci-lint, govulncheck, `go test ./...`, builds `cmd/api` and `cmd/migrate`. Migrator (`cmd/migrate`) or `infra/migrations` changes additionally require `make server-test-integration` (needs `make docker-up` running; the same suite runs in CI's go.yml `database-integration` job against a real PostgreSQL/PostGIS service).
- Android-only change: `make android-check` — `lintDebug`, `testDebugUnitTest`, `assembleDebug` (needs `ANDROID_HOME`; fails fast via `android-sdk-check` otherwise).
- API surface touched: update `packages/api-contracts/openapi.yaml` first, then `make contracts-lint`.
- Version pin touched (mise.toml / wrapper / libs.versions.toml): `make toolchain-check`.
- Dependabot config touched: `make deps-lint`.

## Formatting before commit

- Go: `make server-fix` (tidy + gofmt).
- Kotlin: ktlint-compatible formatting, no wildcard imports.

## CI notes

- Two independent workflows: `.github/workflows/go.yml` and `.github/workflows/android.yml` — a failure in one does not hide the other. go.yml also validates the OpenAPI contract and lints workflow files (actionlint).
- After finishing, review Serena memory accuracy per `AGENTS.md` (update only durable, non-obvious knowledge).
