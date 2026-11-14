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
- All user-facing copy lives in `res/values/strings.xml` with per-feature key prefixes (`home_`, `circle_`, `tracking_`, `sos_`, `map_`, `journey_`, `places_`, `diag_`, `invite_`, `avatar_`); Vietnamese literals stay out of `ui/**` (preview mock data excepted). Resolution convention: helpers that produce copy are `@Composable` and call `stringResource` (e.g. `TrackingScreen.motionLabel`); strings needed inside callbacks/coroutines (`LaunchedEffect`, `scope.launch`, permission callbacks) are hoisted to `stringResource` vals in the composable body. `runCatching`/try bodies must stay pure — composable calls are not allowed inside try-catch, so parse inside `runCatching` and resolve resources after. ViewModel/controller copy travels through state as resource IDs, never as resolved strings: static sentences are `@StringRes Int` fields (`errorRes`, `messageRes`, `noticeRes`, …) and parameterized sentences use `ui/common/UiMessage(id, args)` (SignIn's server-error is the one UiMessage user). ViewModels/controllers reference `com.anve.app.R` directly — plain ints keep them pure JVM-testable without Robolectric — and the hosting screen/host renders with `stringResource` / `stringResource(id, *args.toTypedArray())`. Tests assert on `R.string.*` IDs (`assertEquals(R.string.sos_error_read_failed, state.errorRes)`), never on sentence text and never via `getString`. One known passthrough: `tracking.ConsentResult.message` (outside the ui whitelist) still carries resolved Vietnamese copy, so `HomeUiState.consentMessage` and `TrackingUiState.consentMessage` stay `String?` until `ConsentResult` becomes resource-backed. The invite sharesheet (`invitationShareIntent`) takes its body and chooser title as resolved parameters from `CircleScreen`.
- Every displayed instant formats through `ui/common/AnveTime` (`full` = `dd/MM/yyyy HH:mm` for absolute date-times, `seconds` = `HH:mm:ss` for the history day list); per-screen `DateTimeFormatter.ofPattern` copy is gone — the only other formatter is HistoryScreen's date-only `historyDateFormatter`. Parse failures return the caller's fallback (often the raw ISO string) instead of guessing.
- Destructive-confirm dialogs share one pattern: `TextButton` whose label text is `colorScheme.error` for the destructive confirm, plus a neutral `action_cancel` ("Hủy") dismiss — never `action_back` as a dialog close and never a filled primary Button for deletion confirms. Screen-level status follows the containers: failures render through the shared `ui/common/AnveErrorNotice` (an `errorContainer` Card with optional retry; SignIn/Tracking pattern; HomeContent, Circle, Places, SOS, History and the Live Map overlay included) and notices in a `secondaryContainer` Card, and server-confirmed mutations that would otherwise end silently report through a Snackbar naming the outcome (Places editor save and monitor configure, History retention change — the HistoryViewModel emits a one-shot `RetentionConfirmation(messageRes, days)` StateFlow the screen consumes after showing). SOS statuses render as state-dependent `Surface` chips: open events (server `ACTIVE`, outbox `CONFIRMED`) use the emergency pair from `LocalEmergencyColors`, in-flight states (`QUEUED`/`SENDING`/`UNKNOWN`/`CANCEL_REQUESTED` and pushes not yet accepted) use `secondaryContainer`, finished states go neutral `surfaceVariant` — scanning by color, copy unchanged.
- `HomeScreen` navigation state (`tab`, `secondaryPage`, `sosReturnPage`, `showJourneys`, `showDiagnostics`, `selectedJourney`, `selectedSos`, `selectedInvitation`) uses `rememberSaveable`: enums persist by name with custom `Saver`s that fall back to the default on unknown names; nullable values use savers whose `save` returns null for null (the saveable registry skips nulls, so restore falls back to `init`). Session-driven resets must key on an *actual* user change (a saveable "handled user" marker guards the reset effect) because `collectAsStateWithLifecycle(initialValue = null)` makes the reset effect fire on every recreated composition and would otherwise wipe restored state. Push deep-links do not go through a back stack: before the push effect overwrites navigation it snapshots the whole state into one Serializable `PrePushNav` `rememberSaveable`, and a snackbar effect keyed on that snapshot offers "Quay lại" once per push to restore it — the offer is skipped when the snapshot equals the fresh-session default, because the push displaced nothing.

## Repo-wide

- Contract-first API changes: edit `packages/api-contracts/openapi.yaml` before touching handlers (see `mem:core`).
- Migrations are append-only once merged; every new migration pair is `<version>_<name>.up.sql` + matching `.down.sql` (down requires the file to roll back).
- Comments explain *why*, not *what*.
- Commit messages are imperative and explain intent, not the diff.
- Agents follow `AGENTS.md` (including the Serena section: memories and semantic tools before manual scanning; source is authoritative).

## Dependency automation

- Dependabot covers gomod, gradle, github-actions, docker-compose, docker; minor+patch are grouped, majors are manual except github-actions (which must follow GitHub runtime retirements).
- mise pins and Android platform levels are updated by hand. Validate Dependabot edits with `make deps-lint`.
