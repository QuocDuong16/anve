---
version: 1
slug: "va-com-anve-app-ui-circle-circlescreen-kt-ea94d527"
primary_target: "apps/android/app/src/main/java/com/anve/app/ui/circle/CircleScreen.kt"
related_targets: ["apps/android/app/src/main/java/com/anve/app/ui/circle/InvitationViewModel.kt","apps/android/app/src/main/java/com/anve/app/ui/circle/InvitationDialog.kt","apps/android/app/src/main/java/com/anve/app/ui/circle/InvitationHost.kt"]
---

# Invitation preview and sharing

Mode: Operate. Platform: native Android. Existing AnVề Material world.
Owner selected C on 2026-11-12:
`.impeccable/mocks/feature-completion/invite-preview-c.png`.
The foreground dialog is approved; its synthetic backdrop does not redesign
the existing navigation. Main Circle entry and native sharesheet are reused.

THESIS: Opening an invitation is a request to review a family group, followed
by one explicit decision; it never means automatic membership or GPS sharing.

OWN-WORLD: Existing AnVề teal/sand/ink semantic roles and Material typography,
native AlertDialog, actual text, vector icons and controls; zero raster assets.

STORY: Owner creates one-use invitation, explicitly shares link/code; recipient
opens cold or warm, authenticates if necessary, sees actual group name and
expiry, then accepts or defers. Server revalidates at confirmation. An uncertain
response offers read-only reconciliation, never an automatic new join attempt.

FIRST VIEWPORT: “Tham gia nhóm?”, emphasized group name, one-use and expiry
details, a privacy notice, signed-in account, “Để sau” and “Tham gia nhóm”.
The body scrolls; Material buttons reflow vertically as needed for large type.

FORM: Approved C compact protected decision over current Circle context.
Loading, offline/retry, expired/unavailable invitation, already-in-group,
unknown confirmation and account switch remain explicit states. Back dismisses
without joining. New links replace prior pending links; old callbacks cannot
consume a newer invitation. Codes remain memory-only, expire after 15 minutes,
are removed from Intent data, and never enter logs, diagnostics or saved state.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

Plan: all foreground regions are native Compose controls, text, containers and
Material vectors. No mockup pixels ship. The chat plan review includes this C
dialog and the Journey B sheet because the formal review portal cannot open.
On 2026-11-12 the user approved this current plan through chat:
“Duyệt nha bạn code tiếp đi”. Implement B/C natively; retain the formal portal
as pending and verify the actual native implementation separately. No fake receipt.
