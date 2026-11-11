# M7-B validation record

Status: implementation and automated verification in progress. No physical
device, real Firebase, GPS, battery or OEM results are claimed.

## Decisions and design

- Base: `main` / `6d3cb01` (M7-A merged and main CI green).
- ADR 0012: single active owner journey, exact selected location grants,
  manual arrival, online start, bounded expiry and same-ID reconciliation.
- User selected manual arrival and comp B (lifecycle steps). Creation and
  confirmation comps were subsequently approved. Images and exact prompts
  are under `.impeccable/mocks/`; they contain synthetic data.
- The user explicitly accepted message-based native plan review because the
  component-review portal returned exit 2 (no reviewer browser). The plan has
  23 code regions and zero raster plates. No fake portal receipt was written.
  Formal session `5297b254ace168458f8ce46520c388bcfa0c890f120185a615b7a0ed7d4f5dd1`
  remains pending. This exception is not a final visual or test pass.
- Existing Material theme/system typography wins over image font matching;
  no web catalog font or raster comp is installed in the app.

## Evidence so far

Latest local gate: `make quality-check android-test-build` passed after the
owner-reconciliation guard and the first native review fix batch; 306 JVM
tests, zero failures/errors/skips. No local adb target is present.

Hosted snapshots:

- `0c148a9`: [Go CI](https://github.com/QuocDuong16/anve/actions/runs/43048203691)
  passed; [Android emulator job](https://github.com/QuocDuong16/anve/actions/runs/43048203829/job/130617784072)
  passed 29 tests, no failures/errors/skips. Artifact `13867858190` contains
  14 Journey PNGs including two composed-display dialogs; actual dialog font
  metadata is 1.3. Checkout `37b9ed132e30cb0aad00f03e5c3c39f58b8e7c96` is the
  PR merge commit: its tree and the head tree are both
  `7a77d756708405f0eb5fe90db2577abc2fcbb28b`.
- `32268b4`: [Go](https://github.com/QuocDuong16/anve/actions/runs/43049541098)
  and [Android](https://github.com/QuocDuong16/anve/actions/runs/43049541092)
  workflows passed, including debug/minified release and emulator. This adds
  recipient-removal, offline-start and local-expiry interaction cases. Native
  extraction/review of that artifact is separate from its green job status.
- The first parent visual inspection opened all 14 Journey images and the
  comp comparison. Comp-diff was **0.7156**, below 0.72; its fixed crops falsely
  called visible displaced text/actions missing. The paired crops and whole
  native frame distinguish that limitation from actual missing icons. The
  first fix batch adds Material observation/offline icons and an active step
  marker, improves notice-action contrast, and separates owner/viewer/terminal
  copy. Its new CI capture and final review are still required.
- Some full-height inline image previews omit visible header glyphs. The raw
  top 842×180 pixel bytes of active, viewer, arrived and unknown captures have
  the same SHA-256 prefix `a46bc62797ea44ac`; enlarged crops show the same Back
  arrow (224 light pixels). Inspect original crops before calling these valid
  source images blank or changing the actual UI to fix a preview artifact.

- `mise install` and `make toolchain-check`: pass.
- `make server-check`: pass before the final accuracy/test additions; final
  quality gate still required.
- `make server-test-integration`: pass, including new lifecycle, exact grant,
  consent, rejoin, deletion/retention, cancellation/concurrency, atomic outbox,
  provider fake and strict HTTP/expected-owner tests against real PostGIS.
- Earlier Android check/build passed while the feature was being assembled.
  Later local Gradle checks overlapped and a daemon disappeared under memory
  pressure; no final Android pass is inferred from the earlier check.
- Instrumentation compilation found an invalid extension import for
  `assertDoesNotExist` (a member in the pinned API); import removed. Rebuild/CI
  verification required. Assertions remain enabled.
- Current environment still needs hosted emulator validation. The Android
  workflow runs the complete instrumentation suite and collects native PNGs,
  OS font metadata and test XML. New tests exercise actual Material controls,
  manual confirmation, viewer boundaries, pending/empty/loading/terminal states,
  form selection, recovery controls, dark theme and actual font_scale 1.3.
  Android Keystore/DataStore reopen and owner isolation are tested separately.

## Exit evidence still required

- Final local `make quality-check` or explicitly reported unavailable local
  parts plus equivalent hosted Go/Android/repository gates on the exact SHA.
- Hosted emulator: all tests green, valid phone captures including real dialog
  at font_scale 1.3; inspect original pixels/crops, not a full-height thumbnail.
- Comp comparison and fresh Impeccable finish review, followed by documenter.
- Report Firebase delivery, hardware GPS, battery/OEM and calibrated automatic
  arrival/stale alerts as pending until separate evidence exists.
