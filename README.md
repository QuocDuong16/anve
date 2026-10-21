# AnVề

Monorepo for AnVề — an Android app backed by a Go service and a
PostgreSQL/PostGIS database.

```
anve/
├── apps/
│   ├── android/          # Kotlin + Jetpack Compose client
│   └── server/           # Go backend
├── packages/
│   └── api-contracts/    # OpenAPI contract
├── infra/
│   ├── docker/           # Docker Compose stack
│   └── migrations/       # SQL migrations
├── docs/                 # Architecture decision records
├── scripts/              # Repository quality scripts
├── .github/workflows/    # CI
├── Makefile              # Every development task
└── mise.toml             # Pinned toolchain
```

## Prerequisites

| Tool   | Version | Notes                                                     |
| ------ | ------- | --------------------------------------------------------- |
| mise   | ≥ 2026  | Installs every pinned tool: https://mise.jdx.dev          |
| Docker | ≥ 24    | Runs the PostgreSQL/PostGIS stack                          |
| JDK    | 21      | Installed by mise                                         |
| Go     | 1.27.3  | Installed by mise                                         |
| Android SDK | API 36 | Set `ANDROID_HOME`; not managed by mise                   |

```bash
mise install          # installs the pinned toolchain
cp .env.example .env  # local configuration
```

## Daily workflow

```bash
make help             # list every target
make docker-up        # start PostgreSQL/PostGIS and the API
make docker-down      # stop the local stack
make server-run       # run the API locally (port 8080)
make android-build    # build the debug APK
```

The API is then reachable at <http://localhost:8080>; `GET /healthz` returns
`{"status":"ok",...}`.

## Layout

### `apps/server`

Go 1.27 service using `chi` for routing and `pgx` for the database.

```bash
make server-test      # go test ./...
make server-check     # format, vet, lint, vulnerability scan, test, build
```

The database migration runner lives in `cmd/migrate`; apply the pending
migrations with `make migrate-up` and roll the last one back with
`make migrate-down`.

### `apps/android`

Kotlin + Jetpack Compose client built with Gradle. The Gradle wrapper is
pinned to the same Gradle version as `mise.toml`, and the wrapper verifies
the distribution checksum before it runs.

```bash
make android-build    # app/build/outputs/apk/debug/app-debug.apk
make android-test     # JVM unit tests
make android-lint     # Android lint
```

### `packages/api-contracts`

`openapi.yaml` describes the HTTP surface. Both the Go server and the Android
client are expected to be checked against it.

### `infra`

`infra/docker/docker-compose.yml` runs the local stack and
`infra/migrations` holds the ordered SQL migrations.

## Toolchain

`mise.toml` pins the Go release, the JDK and the Gradle distribution;
`mise.lock` records the resolved artifacts and their checksums. Local
development and CI read the same two files, so a build cannot silently drift
between a laptop and a runner.

`scripts/check_toolchain_versions.sh` fails when a version appears in more
than one place and the copies disagree (for example the Gradle version in
`mise.toml` versus the one in the Gradle wrapper properties). Run it with
`make toolchain-check`.

## CI

Two independent workflows run on every pull request:

- `.github/workflows/go.yml` — format, tidy, vet, lint, vulnerability scan,
  race-enabled tests and binaries for the Go server, plus the OpenAPI
  contract validation.
- `.github/workflows/android.yml` — unit tests, lint and the debug APK for
  the Android app, with the APK uploaded as a build artifact.

Neither workflow depends on the other, so a failure in one does not hide the
other.

## License

Proprietary. All rights reserved.
