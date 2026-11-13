---
version: 1
slug: "com-anve-app-ui-journey-journeyscreen-kt-f20d56ff"
primary_target: "apps/android/app/src/main/java/com/anve/app/ui/journey/JourneyScreen.kt"
related_targets: ["apps/android/app/src/main/java/com/anve/app/ui/journey/JourneyViewModel.kt"]
---

# M7-B Safe Journey

Mode: Operate. Platform: native Android. Scope: owner creation, active journey,
manual arrival/end/cancel, recipient removal and authorized viewer state.

User decisions: manual arrival; option B, steps-led. Keep existing Material 3
identity. All screens and dialogs comp-first. No new main navigation tab.

Ranked structures: 1 summary-led active trip; 2 map-first; 3 lifecycle steps;
4 own/following segmented overview; 5 timeline; 6 contact-led; 7 destination sheet.
The surface roll dealt 4, 3, 2 in that order; the user chose 3 (displayed B).

Approved active reference: `.impeccable/mocks/decision/m7b-checklist.png`.
Creation reference: `.impeccable/mocks/m7b-create.png` (user approved 2026-11-11).
Arrival dialog: `.impeccable/mocks/m7b-confirm.png` (user approved 2026-11-11).
These are synthetic image_gen comps; never runtime evidence or app assets.

## Direction contract

THESIS: Show the journey's lifecycle and the user's next action; three steps
do not pretend to measure GPS route completion or infer safety.

OWN-WORLD: Existing AnVề teal/sand Material 3, system typography and controls.
Native vector icons and a simple step connector are code; no raster plates.

STORY: Choose a saved Place, expiry duration and explicit existing audience;
start online; see observed data age; manually confirm arrival or stop.

FIRST VIEWPORT: Back/refresh app bar, destination and expiry, vertical lifecycle
steps with a tonal current-state area, recipients, offline notice when relevant,
filled arrival and outlined end actions. Content scrolls with OS font scaling.

FORM: Ranked structure 3; user-selected B. seed:a609850031fd0342.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

## States and boundaries

Empty: no active journey, create action, authorized incoming journeys below.
Creation: choose existing Places; no Place or no eligible audience gives an
explicit management action. Requires server consent and network, never toggles
consent silently. Validation/loading preserve owner choices and prevent duplicate
submission. Maps/FCM key absence is not hidden as success.

Active: show server state, optional post-start progress, current recipients and
expiry. No sample means “Chưa có vị trí sau khi bắt đầu”. Owner can remove each
recipient; no additions or destination changes in place. Viewer gets no owner
controls or other recipients. Terminal states say who manually confirmed arrival.

Unknown mutations: persist intent before admission, use the same ID, display
“Máy chủ chưa xác nhận” and owner-bound reconciliation. Ending removes local
JOURNEY priority immediately. Session switch clears ephemeral destinations and
stale responses cannot restore them. Permission/consent errors retain a clear
recovery action; no acquisition on process restart. Dark scheme and actual OS
font_scale 1.3 need emulator captures and interaction verification.

Native adaptation: real Material typography, wrapping text, semantic contrast,
48dp controls and system insets override AI pixel measurements. The comp's
“Đang di chuyển” means active lifecycle only; runtime uses “Hành trình đang diễn ra”
when no measured velocity evidence exists. Ordinary sharing after a journey is
controlled separately and never described as fully stopped by ending the trip.

## Manual plan review exception

The user explicitly accepted message-based plan review for M7-B after the
component-review portal exited 2 (no reviewer browser). All 23 measured regions
are native Compose text, controls, containers, Material vector icons and a
vertical divider; zero raster plates ship. The user approved the creation and
confirmation comps and reuse for end/cancel/empty/error/pending states.
Formal portal session 5297b254ace168458f8ce46520c388bcfa0c890f120185a615b7a0ed7d4f5dd1
remains pending; no receipt was fabricated. Emulator/native review and tests
remain required, and no failing test is waived. This is not a ship verdict.

## 2026-11-12 extension — per-journey automation

Owner selected option B in the current round:
`.impeccable/mocks/feature-completion/journey-automation-b.png`.
The sidecar records approval. Retain the three-step creation form and open
the new options from its summary row; the sheet has independent drafts until
Apply, both switches default OFF and the reminder threshold defaults to
10 minutes when enabled. Existing manual confirmation remains available.

THESIS: Make opt-in automation a deliberate per-trip choice without obscuring
destination, expiry and the selected audience.

OWN-WORLD: Existing static AnVề Material roles, native system typography,
Compose Switch, FilterChip, ModalBottomSheet and vector icons; zero rasters.
Font matching is a measurement aid; DESIGN.md's native Material typography
takes precedence over a web catalog face inferred from generated pixels.

STORY: Choose destination, expiry and viewers; optionally review the sheet,
Apply to this trip, start online, distinguish manual and estimated arrival,
and contact a person when updates are missing without inferring an accident.

FIRST VIEWPORT: Preserve native creation context; the opened sheet leads with
“Cho hành trình này”, helper, separate arrival/reminder groups, threshold
chips only when enabled and an Apply action. It scrolls at enlarged text.

FORM: Approved B sheet plus existing steps-led lifecycle; settings are
immutable once admitted and reset for the next new journey. Back/scrim drops
unapplied drafts. Account changes close private UI and invalidate requests.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

Measured plan: `.impeccable/build/spec.json` has 22 semantic regions, zero
plates. New plan review revision
`95493b755678576b6856795746d56fd24ecf96e1869bf33ea71b9fcce850f69b`
could not open a reviewer browser (serve exited 2). On 2026-11-12 the user
approved the current message-based native plan: “Duyệt nha bạn code tiếp đi”.
This authorizes implementing B and C with native Compose controls while the
formal portal remains pending; no formal receipt or gate pass is fabricated.
Native CI rendering and an independent finish review remain required.

Native translation keeps Material type roles and scrolling at font scale 1.3;
generated text sizes are not fixed Android sp values. Arrival helper copy also
makes the approved automatic consequence explicit: enough evidence ends the
journey and notifies viewers. The comp's two switches remain OFF by default,
while the comparison fixture may turn reminders on to expose the threshold row.
