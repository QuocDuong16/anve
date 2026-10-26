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
- `github.com/google/uuid` for ids (v7 for sessions); `google.golang.org/api/idtoken` verifies Google ID tokens (audience = `AUTH_GOOGLE_CLIENT_ID`, fails closed when unset).
- Build version is injected at link time (`-ldflags "-X main.version=..."`), falls back to the `VERSION` env var, then `"dev"`.
- Database: PostgreSQL + PostGIS (extensions: `postgis`, `uuid-ossp`, `pg_trgm`). Runtime images are digest-pinned; Dependabot moves tag and digest together.
- Integration tests beyond the migrator live in `internal/auth`, `internal/circles` and `internal/locations` (skipped without `TEST_DATABASE_URL`); CI's `database-integration` job runs those packages. Each test gets its own scratch database (`internal/testdb.Scratch`: CREATE DATABASE, apply real migrations, DROP ... WITH FORCE) because `go test` runs packages in parallel and shared tables + TRUNCATE deadlock.
- Concurrency invariants worth knowing: refresh, redeem, circle creation and account deletion serialize on row locks. `DeleteUser` locks the USER row `FOR UPDATE` before owned circles; circle creation holds a FK `KEY SHARE` on that user row, so deletion either sees the committed circle or creation fails its FK (ADR 0005 §4). Owner-deletion vs invite-redemption and vs a competing circle-row holder can deadlock, so `DeleteUser`, `LeaveCircle` and `RedeemInvite` retry up to 3 times on PostgreSQL `40P01`/`40001` (`isDeadlock` in both stores). Deterministic tests use a test-only after-user-lock hook (`internal/auth/export_test.go`) and observed `pg_stat_activity` lock waits; do not reintroduce sleeps as ordering primitives.
- Location ingestion locks in order: users `FOR SHARE` → sessions/devices `FOR SHARE` → membership `FOR SHARE`, then idempotent inserts. This serializes with consent updates, sign-out/device revocation and Circle leave. `internal/locations/export_test.go` exposes after-consent and after-device barriers; the race tests use those plus observed lock waits (consent revocation, device revocation). Duplicate `(user,eventId)` events are reported, not inserted.

## Android app (since M1)

- Retrofit 3 + Moshi reflection (`moshi-kotlin`, ADR 0003 — NO kotlinx-serialization: its compiler plugin fights AGP 9 built-in Kotlin) + OkHttp; one shared Moshi instance.
- DataStore preferences; Keystore AES-256-GCM `TokenCipher` behind an interface (JVM tests fake it).
- M3: Room 2.8.x (`room-runtime`/`room-ktx` + `room-compiler` via KSP 2.3.x, plugin `com.google.devtools.ksp`), WorkManager 2.12 (`work-runtime-ktx`), Play Services Location 21.4 (`FusedLocationProviderClient`). Room schema JSON is exported to `app/schemas` and committed; no destructive migration. Android JVM target stays 17.
- M3 tests: `AdaptiveTrackingPolicyTest` (fake elapsed clock), `LocationQueueRepositoryTest` (fake Room DAO), `LocationSyncEngineTest` (fake gateway/session/store), `TrackingManagerTest` (fake provider/permissions/connectivity/service/scheduler + real repository over fake DAO), `TrackingViewModelTest`. They do not use Robolectric or real SQLite; Room SQL/migrations are validated by build/lint and schema export, not instrumentation (no emulator in CI).
- Credential Manager (`androidx.credentials` 1.6) + `googleid` 1.2.1 for sign-in; Navigation-Compose; ViewModels via manual `viewModel(factory=...)` (no DI framework; `AppContainer` is the root).
- Tests use MockWebServer against the real repository/interceptor; ViewModel tests poll with `awaitUntil` (real IO, not virtual time; its condition is suspend so it can read `SessionStorage`). Repository-level suites (`AuthRepositoryTest`) cover session lifecycle with real HTTP and `InMemorySessionStorage`, independent of ViewModels. The pending-revocation queue is tested with multiple records (two offline accounts, partial success, restart, rotation-then-failure, expiry, active-session isolation, concurrent retry/sign-out); `MockWebServer.dispatcher` is the right tool when responses must depend on the request path/body instead of strict order.
- ViewModel tests never call `Dispatchers.setMain`/`resetMain`. Each ViewModel takes an optional `externalScope` constructor parameter (production falls back to `viewModelScope`); the test passes a `CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())` and cancels it in teardown. The global dispatcher approach caused a CI flake: `resetMain()` raced a pending `viewModelScope` coroutine and the kotlinx test dispatcher threw on concurrent access.
- Offline sign-out feedback is an app-scoped `SessionNoticeStore` (StateFlow in `AppContainer`), not screen state: the repository publishes before `storage.clear()`, Sign-In renders the card, a successful sign-in clears it. It is intentionally not persisted across process death (the durable part is the queue).
- MockWebServer `SocketPolicy.DISCONNECT_AT_START` is only deterministic on the first exchange of a fresh connection; for transport failures after earlier requests, shut the server down (connection refused) or inject an interceptor that throws before the request.
- Retrofit rejects `@Body` on `@DELETE` at call time (not build time) — `deleteMe` uses `@HTTP(method="DELETE", hasBody=true)`. Watch for this class of bug: the proxy throws only when the method is first invoked.

## Migrations

- Custom migrator in `apps/server/cmd/migrate` (`up`/`down` subcommands); no third-party migration library. `make migrate-up` / `migrate-down` run it inside the compose stack.
- Files: `infra/migrations/<version>_<name>.up.sql` with an optional matching `.down.sql`, ordered lexicographically by version.
- M3 migration `0004_locations` creates `location_points` with a PostGIS `GEOGRAPHY(Point,4326)` plus explicit lat/lon, unique `(user_id,event_id)`, capture/received timestamps, retention index and GIST index; FKs cascade from users/devices. The store integration tests live in `internal/locations` and are part of `make server-test-integration` and CI's `database-integration` job.
- Each migration file runs in a single transaction together with the `schema_migrations` bookkeeping update, so a failure never leaves the DB half-migrated. `down` rolls back exactly the most recent applied migration and fails if its `.down.sql` is missing.

## Local config

- `.env` at repo root (see `.env.example`) is loaded and exported by the Makefile; both Docker Compose and the Go binary read the same variables (`PORT`, `LISTEN_ADDR`, `ENVIRONMENT`, `DATABASE_URL`, `SHUTDOWN_TIMEOUT`, read/write timeouts, `TZ` Asia/Ho_Chi_Minh). `LISTEN_ADDR` is empty by default → `127.0.0.1:<PORT>` in development and a startup error elsewhere. Compose sets `LISTEN_ADDR=0.0.0.0:8080` inside the container and publishes the host port on loopback; enabling dev login there additionally needs `AUTH_DEV_LOGIN_ALLOW_NETWORK=true` and `AUTH_DEV_LOGIN_ALLOWED_CIDRS` including the pinned `172.28.0.0/24` edge subnet. `LOCATION_RETENTION_DAYS` (default 30, range 1–365) is the server retention and late-upload window; Compose passes it through.
