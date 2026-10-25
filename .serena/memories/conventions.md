# Conventions

## Go (`apps/server`)

- gofmt + goimports clean; goimports separates the local prefix `github.com/QuocDuong16/anve/server` into its own import group.
- golangci-lint config (v2 format) in `apps/server/.golangci.yml`: standard set plus bodyclose, errorlint, gocritic, gosec, misspell, revive, unconvert; errcheck is excluded in `_test.go` files.
- Errors are wrapped with `%w`; wrapped context starts lowercase.
- Tests use testify and table-driven subtests where they fit.
- Logging only via `log/slog` with the JSON handler.
- `cmd/` entrypoints stay thin; business logic lives in `internal/`.
- Deliberate `#nosec` exceptions (e.g. the migrator reading operator-controlled paths) carry a justification comment — keep the annotation and its rationale when editing those lines.

## Kotlin / Android (`apps/android`)

- Official Kotlin style, ktlint-compatible formatting; no wildcard imports.
- UI lives under `ui/` grouped by feature; Composables are previewable.
- Dependencies are declared in the version catalog `gradle/libs.versions.toml`, never inline coordinates.
- Platform levels (compileSdk/minSdk/targetSdk/buildTools) change only together with AGP, never independently.

## Repo-wide

- Contract-first API changes: edit `packages/api-contracts/openapi.yaml` before touching handlers (see `mem:core`).
- Migrations are append-only once merged; every new migration pair is `<version>_<name>.up.sql` + matching `.down.sql` (down requires the file to roll back).
- Comments explain *why*, not *what*.
- Commit messages are imperative and explain intent, not the diff.
- Agents follow `AGENTS.md` (including the Serena section: memories and semantic tools before manual scanning; source is authoritative).

## Dependency automation

- Dependabot covers gomod, gradle, github-actions, docker-compose, docker; minor+patch are grouped, majors are manual except github-actions (which must follow GitHub runtime retirements).
- mise pins and Android platform levels are updated by hand. Validate Dependabot edits with `make deps-lint`.
