# Feature completion — incumbent-system documentation check

## Disposition and scope

No design-system changes. This is an ordinary native Android extension of **AnVề native Material 3**, covering the approved B Journey options sheet, C invitation preview dialog, and the owner invitation-sharing action. The user selected B/C and approved implementation through “Duyệt nha bạn code tiếp đi”. This report records the completed extension against the incumbent system; it neither replaces the visual world nor promotes a screen composition into global rules.

Written only: `.impeccable/review/feature-completion-documentation.md`. `DESIGN.md` and `.impeccable/design.json` remain untouched. Before writing, both had empty scoped `git status --short` output and `git diff --exit-code HEAD -- DESIGN.md .impeccable/design.json` returned 0. Their SHA-256 values at inspection were:

- `DESIGN.md`: `37b64a41886bb5a17a2e31345d1669490227851f7a9c9fe543214d72c3126ff0`
- `.impeccable/design.json`: `d9fcdc3fce25107cbda7f3bc0a7948d231acbd268746f37b4992f573d3bd8b53`

## Evidence actually checked

Read the shipped Impeccable documenter instructions, `reference/document.md`, repository `AGENTS.md` and RTK guidance; loaded Impeccable context once for JourneyScreen. Read `PRODUCT.md`, the incumbent `DESIGN.md` and its sidecar, both current Journey/Circle surface contracts, the historical finish review, final verdict, and the capture manifest. The documenter's ordinary-extension rule applies: preserve the incumbent system and report drift without repairing it unasked. No fresh design questions or system refresh are required for this authorized scope.

Source checked: `ui/theme/Color.kt`, `ui/theme/Theme.kt`, `ui/journey/JourneyOptionsSheet.kt`, the creation/options, notice and primary-action portions of `JourneyScreen.kt`, `ui/circle/InvitationDialog.kt`, the sharing callback and `InviteRow` in `CircleScreen.kt`, and `InvitationSharing.kt`. Serena's Kotlin overview failed during language-server initialization; these named files/regions were read directly under AGENTS.md's fallback. No broad architecture or backend audit was performed.

Independently opened the two approved synthetic B/C comps and six final native captures: options light, options dark/font 1.3, invitation preview light, invitation preview dark/font 1.3, and owner sharing light/dark. These are actual phone captures; the mockups are design references only. Independently rehashed all 12 manifest captures: **12/12 match**. Read all 12 font-scale sidecars: the two enlarged captures record `androidFontScale=1.3`; the remaining ten record 1.0. The fresh finish reviewer separately opened and validated all 12 captures; this documenter does not claim to have visually reopened the other six.

The manifest attributes the synthetic-fixture phone-emulator packet to source head `4b02f0746237323dcc85f4749268378fa2bffcf1`, run `43203487546`, artifact `13937805348`, with 43 tests, zero failures/errors/skips. It records checkout `57b3a275005f4d5a445ad64cae90bad04959022a` and tree `c1d66cee037c1a956869df020990bce09f39d38c`. GitHub API parent/tree equivalence and external CI results were verified by the parent agent; this pass checked the local record and capture integrity, and did not rerun tests or independently query CI.

## Five-line system summary

1. Palette: incumbent deep teal, warm amber, sand and ink; static light/dark semantic mappings remain unchanged, with dynamic color opt-in.
2. Type ramp: inherited Material 3 `headlineSmall`, `titleMedium`, `bodyMedium`/`bodySmall` and native control labels; no new face or fixed mockup pixel scale.
3. Named color rule: **The Paired Roles Rule** remains the authority for surface/content, selected chips, actions and notices in both themes.
4. Named type/layout rules: **The Native Type Rule** and **The Surface Boundary Rule** preserve sp scaling, dp spacing and surface-specific composition.
5. Named component rule: **The Native State Rule** keeps real Compose controls, disabled states and interaction semantics; image mockups never ship as controls.

## Consistency with the finished extension

| Area | Source and rendered result | System consequence |
| --- | --- | --- |
| Palette and ground | Theme palette/mappings match DESIGN.md. B explicitly uses `colorScheme.surface`. C now uses `surface` with `tonalElevation = 0.dp`; reopened light/dark previews show sand/ink, replacing the earlier lavender foreground. | Reuses established roles; no new color token. The scoped C fix is not a global elevation rule. |
| Content pairs | B selected chips use `primaryContainer`/`onPrimaryContainer`. Filled B/C actions use `primary`/`onPrimary` in light and `primaryContainer`/`onPrimaryContainer` in dark. C notice uses the established light secondary-container pair and dark secondary pair. | Consistent with existing source-specific dark accessibility treatments; do not hardcode frontmatter's light examples into Kotlin. |
| Typography | B title is `headlineSmall`, option titles `titleMedium`, helpers `bodyMedium`; C group name is `headlineSmall`, with native AlertDialog text/action styles. Circle code/expiry use `titleMedium`/`bodySmall`. | Inherits Material typography. The enlarged B/C captures show wrapping Vietnamese text without replacing the native type ramp. |
| Layout | B uses 16dp outer/card padding, 16dp group gaps, 12dp row/group spacing and 8dp chip gaps; creation retains its three-step form. B's bounded LazyColumn keeps Apply outside the scrolling content. C has 16dp group gaps and a scrollable body bounded at 420dp. Circle actions use FlowRow. | Reuses the observed 8/12/16dp rhythm. Local 20/24dp spacing and height bounds remain implementation facts, not a new global spacing/breakpoint scale. |
| Type adaptation | B is taller than its generated comp, with truthful expanded automatic-arrival copy and native controls. The font-1.3 fixture wraps the 30-minute chip to a second row; C wraps the long group/account text while retaining visible actions. | Authorized native adaptation follows the surface contracts and Native Type Rule; no fixed-sp copy of AI typography. No enlarged owner-sharing capture is supplied. |
| Shapes and material | ModalBottomSheet, OutlinedCard, Switch, FilterChip, AlertDialog, Surface, Button and TextButton retain native Material shapes/behavior. Native Email, DateRange and Person vectors plus the account divider are restored in C. | No custom radius/shadow/motion system, no glyph-icon substitute and zero shipping raster plates. The sheet footer explicitly has a 48dp minimum; source/default component sizing is not a whole-app touch-target audit. |
| Flow and copy | Both automation settings start off in the creation source; independent sheet drafts apply only through Apply. Helpers distinguish estimated arrival/manual confirmation. C retains explicit join/defer, privacy warning and separate pending/offline/retry states. | Consistent with voluntary sharing and explicit uncertainty; no safety guarantee becomes a design rule. Full session/backend behavior is outside this documentation pass. |
| Owner sharing | Existing invitation Card retains code/expiry and separate share/revoke actions. Sharing uses a native text/plain chooser with link, manual-code fallback and privacy reminder; launch failure offers the visible code. | Native extension of the incumbent card, not a new card/dialog design. Source or chooser construction does not prove delivery through a target app. |

## Drift and defects not canonized

The light owner-sharing Card still has the incumbent Material default lavender container, and dark uses its default dark container, while C's approved foreground now uses sand/ink. `Theme.kt` leaves additional surface-container and outline roles to Material defaults, exactly as DESIGN.md already explains. This visible variation predates the sharing action and is not a newly approved palette token or a reason to redesign the existing card here. Native outline/switch defaults likewise retain their inherited neutral tones.

DESIGN.md and its sidecar remain a source extraction centered on Diagnostics and representative primitives; the sidecar's evidence description still refers to its original extraction/comp evidence and does not enumerate this extension's later CI captures. That is a bounded documentation snapshot, not evidence that the new captures are synthetic mockups. This report supplies the extension evidence without silently regenerating the system or its static HTML panel illustrations.

The final B diff remains **0.5516 / contradicted**. Historical fixed-coordinate crops were displaced by the documented native reflow; the finish review evaluated that adaptation separately. No numeric pass, altered comp/spec, or system token was invented to make the score pass. The resolved C ground and missing metadata landmarks are fixes, not defaults future screens should inherit as defects. Existing navigation and fixture system-bar presentation remain outside the scoped foreground review.

## Review and validation boundaries

The final native verdict is `ship` **for the three scored fixes and the first scoped owner-sharing review**; it is not a fresh whole-product audit. No scoped QUALITY BAR is owed for this ordinary extension. The formal component portal remains pending under the user's explicit chat exception. At inspection, build-state still has `plates` open, later phases pending, and `finish` null; its notes truthfully record native equivalents, inapplicable web detector/hero checks, and the refused phase advance/finish attempt. The “no plates owed” gate summary does not close those phases. This report neither fabricates a receipt nor claims machine/tool ship.

No source edits, tests, commits, formal phase changes or system-file changes were made by this documenter. Release AAB/signed-startup validation is separate and was not evidenced in this pass. Phone CI emulator fixtures do not establish physical-device, OEM, real GPS, battery, real-provider, chosen-app invitation delivery, tablet, or whole-M8 readiness. No durable design-system change is introduced, so no memory update is needed merely to record this task.
