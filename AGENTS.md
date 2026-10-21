# AGENTS.md

Working agreements for coding agents and contributors in this repository.

## Before you start

```bash
mise install          # pinned toolchain (mise.toml + mise.lock)
make toolchain-check  # the pins in this repo agree with each other
```

`mise.toml` and `mise.lock` are the single source of truth for the Go
release, the JDK and the Gradle distribution. Never bump a version in only
one place — change `mise.toml`, run `mise install`, and commit the updated
`mise.lock`.

## Layout rules

- `apps/server` — the Go service. Business logic goes in `internal/`; the
  `cmd/` directory holds thin entrypoints only.
- `apps/android` — the Compose client. UI lives under `ui/`, grouped by
  feature.
- `packages/api-contracts` — the OpenAPI document. Change the contract
  before changing the handlers.
- `infra/migrations` — ordered SQL migrations, `<version>_<name>.up.sql`
  with a matching `.down.sql`. Migrations are append-only once merged.

## Make targets

Use the Makefile rather than raw commands; CI runs the same targets.

| Task                | Target                |
| ------------------- | --------------------- |
| Full local stack    | `make docker-up`      |
| Stop local stack    | `make docker-down`    |
| Go format/tidy      | `make server-fix`     |
| Go checks           | `make server-check`   |
| Android build       | `make android-build`  |
| Android checks      | `make android-check`  |
| Everything          | `make quality-check`  |

## Conventions

- Go: `gofmt` clean, `go vet` clean, `golangci-lint` clean. Errors are
  wrapped with `%w` and wrapped context starts lowercase.
- Go tests use `testify` and table-driven subtests where they fit.
- Kotlin: official style, `ktlint`-compatible formatting, no wildcard
  imports. Composables are previewable.
- Comments explain *why*, not *what*. A comment that restates the code is
  noise.
- Commit messages are imperative and explain the intent, not the diff.

## Definition of done

A change is done when `make quality-check` passes and the relevant CI
workflow is green.
