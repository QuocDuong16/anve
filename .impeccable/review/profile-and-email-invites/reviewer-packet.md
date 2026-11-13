# Native finish review packet — profile and email invitations

Project: /home/niand/Workspace/anve. Native Android, Operate, ordinary extension.
User asked to audit remaining product requirements and implement them; selected
Photo Picker uploads, email-addressed FCM, then B bottom sheet for each flow.
Current chat native-plan exception and its formal boundary are recorded in
plan-exception.md. Keep incumbent theme/navigation and existing C confirmation.

## Direction and quality bar

Read PRODUCT.md, DESIGN.md and .impeccable/design.json. Contracts:
.impeccable/surfaces/anve-profile-avatar.md and the profile/email extension of
.impeccable/surfaces/va-com-anve-app-ui-circle-circlescreen-kt-ea94d527.md.
Approved references (synthetic, not working-app screenshots):
.impeccable/mocks/profile-and-email-invites/avatar-b.png and email-invite-b.png.
Generated botanical images are runtime-content examples, never bundled artwork.
Reference backdrops are illustrative; do not demand new menus/navigation.

Quality bar: clear native consent/confirmation, B reading order and grouping,
incumbent teal/sand/amber/ink Material system type, native sheet motion/insets,
real text and accessible controls with 48dp targets, 4.5:1 body/control text
and 3:1 nontext contrast, scrolling/reflow at OS font scale 1.3 with the primary
footer reachable. No screenshot placed into the app, no fabricated safety,
provider or real-device claims. Native HTML/CSS detector was intentionally not
run; judge using Android conventions and craft-floor/platform references.

## Implementation and behavior

Primary artifacts: ui/avatar/AvatarSheet.kt, AvatarImage.kt, AvatarEditorHost.kt,
AvatarController.kt; ui/circle/EmailInvitationSheet.kt, ReceivedInvitations.kt,
PrivateInvitationsController.kt; CircleScreen.kt and home/HomeScreen.kt under
apps/android/app/src/main/java/com/anve/app. Data boundaries: data/avatar,
data/circles/PrivateInvitationRepository.kt and data/sos/PushSignal.kt,
PushDestination.kt. See docs/implementation/profile-and-email-invitations.md,
ADR 0017 and OpenAPI for privacy, creation fences and authorization.

Photo selection/removal are drafts until Save; canceled picker/Back/account
change must not upload. Uncertain writes only read metadata. Private inbox
shows inviter context, review opens existing explicit C confirmation; opening,
FCM/tapping/previewing never joins or enables GPS. Hide is receiver-private.
Creation responses expose no email registration/token/delivery result. Note
which interactions are proven by source/tests and which screenshots cannot
establish; do not treat local builds as device/provider evidence.

## Required evidence for full native review

All eleven captures in native/ are required: avatar-preview-light.png,
avatar-preview-dark-font130.png, avatar-removal-draft.png, avatar-offline.png,
avatar-unknown.png, avatar-image-error.png, email-invitation-light.png,
email-invitation-dark-font130.png, email-invitation-unknown.png,
email-invitation-link-mode.png, received-invitation-context.png.
Provenance: native/manifest.json, Android run 43285537402 at source ca13b4f.
API 35: 53 tests passed; API 26: 3 photo tests passed. 330 JVM tests and local
make quality-check/android-test-build passed. Signed startup job pending.
Captures use fixture runtime photos and isolated sheet/context harnesses;
not a claim that the entire production host was captured end to end.

Read both diff/avatar-final/ and diff/email-final/ report.json, side-by-side,
heatmap and named paired regions. Full-frame numeric results are NOT passes:
avatar 0.4075/missing; email 0.5579/drift. Comps 928x1695 versus native
842x1870 and system typography/native reflow/runtime photo/illustrative
background are explicitly scoped in plan-exception.md and surface contracts.
Judge actual grouping, action order, native quality and these exceptions
without relabeling numerical contradictions as passes. Build/spec snapshots:
avatar-build-state.json, email-build-state.json, avatar-spec.json,
email-spec.json; current .impeccable/build/state.json stays PLATES/pending.
No HTML/CSS detector ran because this is native Compose.

Report the shipped finish-reviewer's five contract sections and disposition;
write only finish-review.md in this directory. Do not edit source or global
system files. Name any required correction or invalid capture explicitly.


Post-review correction: verdict.md scores both findings resolved using the
two replacement captures bound individually to 17b0fcd/run43289266521.
Other nine captures remain ca13. The fix-list ship does not change the initial
whole-surface review record or formal PLATES status. See evidence.md and
current PR checks for exact-source automated outcomes.
