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

## Addressed invitation extension

Owner selected email-addressed FCM invitations and B creation bottom sheet:
`.impeccable/mocks/profile-and-email-invites/email-invite-b.png`. Approval is
recorded in its sidecar. Existing C confirmation and current navigation remain.
The creator form offers explicit email versus ordinary link/code modes; email
mode binds acceptance to that signed-in email. Use native radio/choice controls,
email keyboard/field, helpers, privacy notice, expiry line and Create footer.
Native type/sp scaling and scrolling override generated pixel sizes.

THESIS: Invite a specific person without exposing account existence or implying
that a notification has been delivered or accepted.

OWN-WORLD: Incumbent Material sheet, actual text/vector/field/button controls;
no mockup raster, new tab or new theme.

STORY: Owner chooses mode, enters email if needed, explicitly creates once,
then can share the resulting code/link. The private recipient inbox shows
group, inviter and expiry; View opens the existing protected C confirmation.
Hide is receiver-private; defer does not join. Opening/push never starts GPS.

FIRST VIEWPORT: Sheet title, mode choice, conditional email field/binding
helper, cautious possible-notification copy, privacy notice, expiry and Create.
Received invitations are a bounded section in the current Circle screen.

FORM: B creator sheet plus reused C recipient confirmation. Preserve drafts
on validation/network error; pending and unknown creation keep the same UUID
and reconcile by readback, never auto-create another invitation. No lookup
result, recipient avatar, provider-accepted/delivered badge or email-discovery
UI exists. Account changes invalidate draft/private response publication.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
