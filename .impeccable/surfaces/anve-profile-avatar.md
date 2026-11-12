---
version: 1
slug: "anve-profile-avatar"
primary_target: "apps/android/app/src/main/java/com/anve/app/ui/home/HomeScreen.kt"
related_targets: ["apps/android/app/src/main/java/com/anve/app/ui/home/HomeViewModel.kt", "apps/android/app/src/main/java/com/anve/app/ui/avatar/AvatarSheet.kt", "apps/android/app/src/main/java/com/anve/app/ui/avatar/AvatarImage.kt"]
---

# Profile-photo extension

Mode: Operate. Platform: native Android. Inherit DESIGN.md; no new world.
Owner selected Photo Picker uploads and avatar B bottom sheet through the
structured approval. Approved comp:
`.impeccable/mocks/profile-and-email-invites/avatar-b.png`.
The synthetic account-menu backdrop is not a navigation redesign.

THESIS: Review a deliberately selected profile photo before sharing it with
current Circle members, with an equally clear way to remove it.

OWN-WORLD: Existing teal/sand/ink Material roles, system type, native sheet,
Image, buttons and Android Photo Picker. No broad photo/storage permission.
The botanical comp image is synthetic sample data, never a bundled default
avatar. Runtime image content comes from the user's picker and authorized API.

STORY: Open avatar controls; choose one image in the OS picker; decode/crop
and reduce in memory; review; explicitly save or remove. Back discards an
unsubmitted draft. Uncertain saves read metadata; no automatic upload replay.

FIRST VIEWPORT: Handled sheet, title and privacy helper, generous circular
preview, distinct choose/remove actions, upload-privacy copy and Save footer.
Body scrolls and footer stays reachable with native type, insets and font 1.3.

FORM: Approved B structure, native Material type/48dp targets. Empty/failed
image uses initials with an honest state, not a substituted stock photo.
Decode failure, canceled picker, offline, busy, conflict, unknown/readback and
account switch have distinct behavior. A pending image is process-memory only;
rotation may discard a draft, never automatically upload it. Show the account
identity from current session metadata; stale callbacks cannot write as a new
owner. Picture reads have no disk cache or unauthenticated public URL.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

The supplied user photo is runtime content rather than authored production
artwork: preserve the image region as an actual Image view and verify it with
an explicitly synthetic test image. Do not crop a mockup into the app or claim
that code draws a photograph. No system-token changes are authorized.

Native Material system typography is the explicit incumbent override of web
font fingerprint rankings. See the scoped native-plan exception record.
