# Tech Stack

## Where versions live (do not memorize the numbers)

- Go release, JDK (temurin), Gradle distribution, golangci-lint, govulncheck, gosec, actionlint: pinned in `mise.toml`, resolved by `mise.lock`. To bump: edit `mise.toml`, run `mise install`, commit the lock. Never bump in only one place.
- Android/AGP/Kotlin/Compose versions: version catalog `apps/android/gradle/libs.versions.toml`. Platform levels (compileSdk/minSdk/targetSdk/buildTools) move in lockstep with AGP, never on their own; they are not Dependabot territory.
- The Android SDK itself is NOT managed by mise: CI installs it via `android-actions/setup-android@v4` (v3 is dead — it ran the removed `sdkmanager tools`), locally set `ANDROID_HOME` (Makefile default `~/Android/Sdk`).

## Non-obvious Android build facts

- AGP 9 compiles Kotlin through its built-in Kotlin support: the `org.jetbrains.kotlin.android` plugin is deliberately NOT applied. Only `org.jetbrains.kotlin.plugin.compose` is applied; its version is the project-wide Kotlin version and must match the Kotlin compiler AGP uses. Do not "fix" a build by adding the Kotlin Android plugin.
- The Gradle wrapper is pinned to the same Gradle version as `mise.toml` and verifies the distribution checksum; `scripts/check_toolchain_versions.sh` fails when any version appears in two places and disagrees.
- Android JVM target is 17 (lower than the JDK 21 used to run Gradle). `buildConfig = true` is enabled explicitly because the app reports its version on the splash screen.
- Debug build type gets `applicationIdSuffix ".debug"`.

## Go server

- chi v5 for routing, pgx v5 (`pgxpool`) for PostgreSQL, `caarlos0/env/v11` for env config, testify for tests, `log/slog` with a JSON handler for all logging.
- Build version is injected at link time (`-ldflags "-X main.version=..."`), falls back to the `VERSION` env var, then `"dev"`.
- Database: PostgreSQL + PostGIS (extensions: `postgis`, `uuid-ossp`, `pg_trgm`). Runtime images are digest-pinned; Dependabot moves tag and digest together.

## Migrations

- Custom migrator in `apps/server/cmd/migrate` (`up`/`down` subcommands); no third-party migration library. `make migrate-up` / `migrate-down` run it inside the compose stack.
- Files: `infra/migrations/<version>_<name>.up.sql` with an optional matching `.down.sql`, ordered lexicographically by version.
- Each migration file runs in a single transaction together with the `schema_migrations` bookkeeping update, so a failure never leaves the DB half-migrated. `down` rolls back exactly the most recent applied migration and fails if its `.down.sql` is missing.

## Local config

- `.env` at repo root (see `.env.example`) is loaded and exported by the Makefile; both Docker Compose and the Go binary read the same variables (`PORT`, `ENVIRONMENT`, `DATABASE_URL`, `SHUTDOWN_TIMEOUT`, read/write timeouts, `TZ` Asia/Ho_Chi_Minh).
