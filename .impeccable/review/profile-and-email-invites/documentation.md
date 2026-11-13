# Documenter record — profile photos and addressed invitations

Decision: ordinary native Android extension; preserve the incumbent visual
system. No changes to `DESIGN.md` or `.impeccable/design.json`. This record
documents observed implementation and evidence limits; it is not a finish
verdict or a formal Impeccable gate receipt.

## Sources checked

Read the shipped `agents/impeccable_documenter.toml` and full
`reference/document.md`, `PRODUCT.md`, `DESIGN.md`, `.impeccable/design.json`,
the `anve-profile-avatar` surface brief and addressed-invitation extension of
the `CircleScreen` surface brief. Checked `reviewer-packet.md`,
`plan-exception.md`, `finish-review.md`, `verdict.md`, `evidence.md`, native manifest and
`docs/implementation/profile-and-email-invitations.md`.

Inspected actual `Color.kt`, `Theme.kt`, `AvatarSheet.kt`, `AvatarImage.kt`,
`AvatarEditorHost.kt`, `EmailInvitationSheet.kt`, `ReceivedInvitations.kt` and
the `17b0fcd` production/fixture correction diff. Serena Kotlin initialization
failed; exact named-file reads used the repository's documented fallback.
Source establishes implementation, while the screenshots establish only
their recorded native fixture renders.

Independently opened the retained `ca13b4f` captures
`avatar-preview-light.png`, `email-invitation-dark-font130.png`,
`avatar-image-error.png` and `received-invitation-context.png`. The existing
finish reviewer covers all eleven states and the paired comp comparisons;
this documenter pass does not claim to repeat that complete finish review.
On follow-up, reopened the replaced `avatar-image-error.png` and
`received-invitation-context.png`, now bound individually to `17b0fcd`,
and checked the fresh two-fix verdict. The current images show the complete
retry badge and contrasting dark system icons, respectively.

## Five-line system summary

1. Palette: inherited teal actions, warm amber attention, sand light surfaces and ink dark surfaces; no new palette primitives.
2. Type ramp: native Material `headlineSmall`, `titleMedium`/`titleSmall`, `bodyMedium`/`bodySmall`, component labels; avatar initials use `titleLarge`.
3. Named rules: Paired Roles and Native Type remain applicable; semantic content/container pairs and Android sp scaling govern both sheets.
4. Named rules: Surface Boundary keeps B-sheet grouping, 160dp avatar and local 12dp corners scoped to these surfaces; no global composition/radius token is introduced.
5. Named rules: Native State keeps actual Compose selection, fields, buttons, disabled/loading behavior, scrolling and accessible recovery controls.

## Observed extension of the incumbent system

Both sheets use native `ModalBottomSheet`, surface-colored containers,
scrolling `LazyColumn` bodies and a separate reachable full-width footer.
Observed source spacing reuses 4/8/12/16dp steps. The local maximum body area
uses 86 percent of configured screen height; actions have explicit 48dp
minimum height. `FlowRow` supports reflow. These describe implementation,
not new cross-product layout tokens or a universal breakpoint system.

Primary footer actions use `primary`/`onPrimary` in light and
`primaryContainer`/`onPrimaryContainer` in dark. Outlined/text action content
uses `primary` in light and `onSurface` in dark. The invitation privacy notice
uses `secondaryContainer`/`onSecondaryContainer` in light and
`surfaceVariant`/`onSurfaceVariant` in dark. This preserves the existing
semantic-role strategy without turning a single light color into a Kotlin
literal. No app-wide typography, shapes, motion, elevation or theme override
is added.

The avatar reading order remains title, audience, circular preview,
choose/remove, upload-privacy copy and Save. `AvatarEditorHost` renders an
actual 160dp `Image` or initials through `AvatarFrame`; selected bitmap bytes
and authorized API reads supply runtime content. The two-color test image
is a synthetic native fixture. The botanical comp is a reference, not bundled
default content. The photo/initials surface alone is circularly clipped;
the corrected outer `Box` hosts the 48dp retry control outside that clip.
The control remains a labeled native `IconButton`.

The email creator uses a real selectable radio group, conditional
`OutlinedTextField` with Email keyboard/Done IME action, binding helper,
Material vectors, cautious possible-notification wording, privacy notice,
native expiry menu and Create footer. Local `RoundedCornerShape(12.dp)`
groups are surface details rather than a new global radius scale.
Unknown outcomes replace the write footer with “Kiểm tra kết quả”.
Received invitations use real group/inviter/expiry text plus “Xem lời mời”,
receiver-private “Ẩn với tôi” and refresh actions. These maintain the existing
Circle context and explicit protected confirmation flow.

All sampled controls, labels and icons are native Compose content. No comp
crop supplies app chrome, a field, a button, a photo or a screen. The source
read and retained fixture images agree on this material boundary. Runtime
photo provenance is the picker/API path, not an authored production plate.
Documentation-panel HTML/CSS and font-analysis files remain review aids;
they do not define native fonts or production assets.

## Evidence and unresolved finish boundary

The native manifest is now mixed-source: nine unchanged captures remain bound
to `ca13b4fc717901e0735598cdad125206f831fa6b` / Android run `43285537402`;
the two replaced error/context captures are bound per file to
`17b0fcdb45fdaf66017cf602db59ac724f473cbe` / run `43289266521`, attempt 1.
The original `ca13b4f` evidence record reports 330 JVM tests, 53 API 35 instrumentation tests,
three API 26 photo tests and successful signed fixture startup, resume and
process recreation. Its provider/device fields remain false. These are
automated fixture results, not full production-host or real-provider proof.

The historical error capture clipped the retry control and the historical
received-context capture had white system icons against pale sand.
`17b0fcd` changes the production avatar overlay hierarchy and the received
fixture's light status/navigation bar appearance. Source inspection verifies
those edits; the two replacement images now verify the complete visible retry
arrow/control outside the photo clip and dark clock/network/battery icons on
sand. Both named tests passed. The fresh reviewer scores both findings resolved
and records `disposition: ship` for those two fixes only, with no visible
regression in that correction scope. It does not approve the whole surface,
prove production-host integration or change the raw comp measurements.

The coordinating implementation report records local full quality validation
(330 JVM tests) and instrumentation compilation passing for `17b0fcd`, with
Go, API 26, build and signed-smoke hosted jobs passed. API 35 has one test
failure in attempt 1 (52/53): OS `font_scale` setup before Activity/UI, separate
from the two passing correction tests. The exact-head rerun is in progress
at this handoff; no overall green result is asserted. These job statuses are
coordinating-run evidence, not an independent CI execution by this documenter.
Nine original PNGs were not recaptured and must not be relabeled as `17b0fcd`.
Final exact-commit CI status belongs in the delivery/PR evidence.

The native-plan exception permits incumbent system type, native aspect
ratio/insets/reflow, runtime photos and exclusion of illustrative backdrop
menus. It does not convert the numerical comp comparisons into passes:
avatar remains 0.4075/missing and email remains 0.5579/drift at their recorded
full-frame comparison scope. `.impeccable/build/state.json` still reports
`plates`; a post-review formal finish attempt again refused completion for
plates/hero/sections/motion/responsive. Formal component/PLATES completion
remains pending. No receipt,
production image plate or gate pass is fabricated here. Motion, complete
production-host integration, physical-device/OEM/GPS/battery behavior and
real Photo Picker/FCM/provider delivery retain separate verification gates.

## Preservation and drift not canonized

The incumbent files are deliberately a scoped extraction: `DESIGN.md` names
Diagnostics/Home/SignIn samples and the sidecar records that older extraction
scope, including a source/comp-only evidence description. They do not yet
inventory these sheets or their later hosted fixture evidence. This is
pre-existing documentation coverage drift, not permission to regenerate
the system during an ordinary extension. Existing palette/type/semantic
rules still match the inspected implementation. No unrelated drift is fixed.

The historical clipped retry and low-contrast fixture system icons are
defects, never design-system rules. Both are resolved within the fresh
two-fix verdict and individually bound replacement-capture scope.
No numeric radius, custom type scale, bundled botanical image, forced gate,
delivery badge or whole-screen mockup is canonized to explain them away.

Preserved SHA256 values:

- `DESIGN.md`: `37b64a41886bb5a17a2e31345d1669490227851f7a9c9fe543214d72c3126ff0`
- `.impeccable/design.json`: `d9fcdc3fce25107cbda7f3bc0a7948d231acbd268746f37b4992f573d3bd8b53`

Only this report is written. No durable global design decision changes;
no memory update is warranted solely to record this documentation pass.
