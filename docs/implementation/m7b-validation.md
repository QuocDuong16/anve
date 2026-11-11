# M7-B validation record

Status: Implemented — Automated Verified at `bcde472`; native correction
review resolved and final native rendering accepted by the user. The formal
portal remains pending under an explicit completion exception. No physical device,
real Firebase, GPS, battery or OEM results are claimed.

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
  copy. The `0ddb112` snapshot below verifies that batch.
- Some full-height inline image previews omit visible header glyphs. The raw
  top 842×180 pixel bytes of active, viewer, arrived and unknown captures have
  the same SHA-256 prefix `a46bc62797ea44ac`; enlarged crops show the same Back
  arrow (224 light pixels). Inspect original crops before calling these valid
  source images blank or changing the actual UI to fix a preview artifact.

- `0ddb112`: [Go CI](https://github.com/QuocDuong16/anve/actions/runs/43051897030)
  and [Android CI](https://github.com/QuocDuong16/anve/actions/runs/43051896910)
  passed, including JVM/lint/debug/minified release and emulator. Emulator job
  `130630974137` passed **32 instrumentation tests**, zero failures/errors/skips.
  Artifact `13871390173` contains 17 Journey PNGs and OS font metadata. Dark
  captures and the composed-display dialog use actual Android `font_scale=1.3`
  set before Activity creation; the setting is restored afterward.
  Checkout `45852b376c60cc8a92fe916f33a68f27b2da1498` is the PR merge commit;
  its tree and head `0ddb1123098128d5e66672494bf8f00bea4a714b` both equal
  `66bf63d7acb5967a5290e374b6f711936d5b8d97` (GitHub Git commit readback).
- Final native dimensions are 842×1870 px at density 327, approximately
  412×915 dp. The approved comp is 841×1870 px; a one-pixel density rounding
  difference is recorded rather than rescaling the native original. No tablet
  coverage is claimed. Host-only dialog captures are paired with real composed
  display captures named `journey-dialog-*`; the latter verify the modal.
- The second parent visual batch inspected all final states, the full comp
  pair and the worst fixed-coordinate crops. Final comp-diff is **0.7509**,
  tool verdict **drift** (structure 0.6012, color 0.8965, detail 0.6758).
  Text marked missing in fixed crops is visible at moved native coordinates;
  native typography, extra accuracy/mock/ETA clarification and real controls
  differ from the image. This is not a formal fidelity pass or user ship receipt.
- `mise install`, `make toolchain-check`, and final
  `make quality-check android-test-build`: pass. Local JVM total: **306**, zero
  failures/errors/skips. The final gate supersedes earlier build assembly
  failures (invalid instrumentation extension import and overlapping Gradle
  daemons); assertions remain enabled and no timeout/skip workaround was used.
- `make server-test-integration`: pass against real PostGIS, including
  lifecycle, exact grants, consent/rejoin/deletion/retention, cancellation and
  concurrent create, atomic outbox, fake provider, strict HTTP/expected-owner
  and post-start capture quality/progress tests.
- New instrumentation covers actual Material controls, arrival/end dialogs,
  viewer/recipient boundaries, pending/empty/loading/terminal states, form
  selection, offline start, local expiry and recovery controls. Keystore-sealed
  DataStore reopening and owner isolation are tested separately. These use
  synthetic fixtures, not a live backend-to-Firebase end-to-end deployment.

## Evidence outside automated acceptance

- Native review/documenter and final user exception are recorded below. The
  formal component portal still has no receipt; its completion requirement was
  explicitly waived for M7-B, not for future UI.
- Real Firebase delivery/read behavior, hardware GPS, battery/OEM and calibrated
  automatic arrival/stale alerts need separate evidence. Automated CI does not
  establish those outcomes or complete all of M7.

## Independent native finish review

The fresh reviewer inspected all 17 current captures and worst paired crops.
Disposition: **fix**. Native typography, colors, controls, steps, uncertainty
copy and enlarged-text dialog are acceptable adaptations of the approved comps.
Two material findings: selected viewer details need the owner's name even on
push/deep-link entry; persisted build gates/formal portal are still open.
The attribution fix adds the authorized owner's name beside the destination
and an instrumentation assertion. The verdict pass below scores that correction
only; it does not replace the full review or close formal workflow gates.
The workflow finding is not silently converted into a ship receipt.

Read-only Impeccable documenter compared PRODUCT/DESIGN/sidecar, the finished
Journey source and shared Color/Theme, direction/spec/state and native captures.
No global palette/type/shape/token change or raster asset was introduced;
incumbent DESIGN.md and design.json were preserved. Pre-existing sidecar
freshness drift is reported, not repaired. Serena Kotlin initialization failed;
known-file source fallback was used without language-server repairs.

At `556a880`, local `make android-check android-test-build` and the complete
`make quality-check` both pass after the owner-attribution correction. The
viewer instrumentation now asserts the owner's visible name on directly
selected details without relying on a parent list. Hosted recapture is recorded below; earlier captures alone do not prove this
correction rendered.

### Attribution correction evidence

At `556a8805e57b6fbc9785c2ef3a8f282f32ee76d6`,
[Go CI](https://github.com/QuocDuong16/anve/actions/runs/43054948889) passed all
jobs. [Android emulator job](https://github.com/QuocDuong16/anve/actions/runs/43054948779/job/130641783709)
passed **32 tests**, zero failures/errors/skips; artifact `13871975076` contains
17 Journey PNGs and actual OS font metadata. PR merge checkout
`5da45e078d703a5401294481353295c702795119` and head share tree
`2b30557a3b6fba900f00913d832c101781bbc112`. The app build/release job is tracked
separately from that successful emulator evidence.

The same reviewer scored **viewer attribution resolved**: the new viewer
capture visibly shows “Hành trình của An” under the destination with readable
hierarchy. **Persistence unresolved; disposition fix**: formal plan receipt is
pending, plates is open, later phases pending, finish null. Normal advance
refuses that receipt and no override/approval was fabricated. Final workflow
completion requires resolving the route or an explicit user exception covering
final completion. The previous user exception covers the component plan only.

The new full native comparison remains approximately 0.75 overall with the same
fixed-coordinate crop limitations and explicit native adaptations. No measured
fidelity pass is claimed. The capture files are reproducible CI artifacts, not
shipping app assets. A final documentation-only commit may reference this tested
code snapshot; its own CI result must be checked separately before merge.

### Final automated snapshot and user decision — 2026-11-12

At `bcde47253afbba39ac9e9e3e7bc9faf9512fb3b4`,
[Go CI](https://github.com/QuocDuong16/anve/actions/runs/43056495298) and
[Android CI](https://github.com/QuocDuong16/anve/actions/runs/43056495305)
passed all jobs, including race/PostGIS/repository checks, JVM/lint/debug,
minified release and emulator. Artifact `13871548653` reports **32 tests, zero
failures/errors/skips** with native captures/OS font metadata; debug APK artifact
is `13872856062`. This commit changes only two documentation files relative to
`556a880`; the reviewed source and UI are unchanged.

After displaying the current owner/viewer/dark-font130 dialog captures, the
user was explicitly asked to accept the native rendering as the final M7-B UI
reference and allow completion/merge with green CI despite the pending formal
portal. The user replied **“Tiếp nhé và tôi duyệt”**. This is a final-render and
completion exception, beyond the earlier component-plan exception. Comp-first
remains mandatory for future screens/phases.

The same reviewer then returned **disposition: ship** for its two findings:
viewer attribution resolved; persistence accepted by explicit user exception.
This is a verdict pass over those findings, not a new whole-surface audit.
The documenter outcome remains applicable because no UI source changed later.

`build-phase finish --disposition ship` was attempted and refused because
plates/hero/sections/motion/responsive are not closed. The CLI does not recognize
the message-based exception; the actual phase records and formal receipt remain
open. No receipt, closed gate or numeric fidelity pass was manufactured. Delivery
uses the user's explicit completion exception, not a claim the tool gate passed.
Physical-device/GPS/battery/OEM, real Firebase and calibrated automatic arrival/
stale alerts remain unverified or Planned as applicable.
