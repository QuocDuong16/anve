# IMPLEMENTATION_PLAN.md — AnVề Stitch UI/UX Overhaul & Landing/Contact System

Master checklist for deeply synchronizing the **AnVề** codebase with the Google Stitch design system (`projects/13521834583655677229` — **Warm Tactile Minimalism**) and maintaining the server-side Landing Page & Contact/Notification system.

---

## Phase 1: Design System Tokens, Extended Stitch Palette & Localization
- [x] Update `.impeccable/design.json` & `DESIGN.md` to match Google Stitch **Warm Tactile Minimalism** (Forest Green `#3D602F` / `#557945`, Soft Blush `#F1DDE4` / `#F6E2E9`, Paper Cream `#FDF9F2` / `#FEFAF3`, `Be Vietnam Pro` typography).
- [x] Migrate base tokens in `Color.kt`, `Theme.kt`, `Type.kt`, `colors.xml`, `themes.xml`, and `ic_launcher_foreground.xml`.
- [x] Extend `Color.kt` & `Theme.kt` with Stitch fixed/tactile palette (`StitchColors` via `LocalStitchColors`: `primaryFixed`, `onPrimaryFixed`, `primaryFixedDim`, `secondaryFixed`, `onSecondaryFixed`, `secondaryFixedDim`, `livePulse`, `liveContainer`, `onLiveContainer`, `transitContainer`, `onTransitContainer`, `cardTactile`, `onCardTactile`, `hairline`), and add WCAG contrast tests in `ThemeContrastTest.kt`.
- [x] Expand `apps/android/app/src/main/res/values/strings.xml` with complete Stitch UI copy, subtitles, status badges, metric labels, filter chips, and reassuring footer quotes (zero hardcoded Vietnamese in Compose source).

## Phase 2: Shared Stitch UI Kit Component Library & Assets (Stitch Screen 52)
- [x] Create `apps/android/app/src/main/java/com/anve/app/ui/common/StitchComponents.kt` with reusable Stitch Screen 52 primitives: `StitchBadgeKind`, `StitchSemanticBadge`, `StitchLivePulseDot`, `StitchLiveSharingPill`, `StitchMetricTile`, `StitchTimelineItem`, `StitchCategoryIconBox`, `StitchEmptyStateCard`, `StitchLoadingSkeletonCard`, and `StitchReassuranceFooter`.
- [x] Create `apps/android/app/src/main/java/com/anve/app/ui/common/StitchUiKitShowcase.kt` implementing all 8 Stitch Screen 52 UI Kit sections (`AnVe UI Kit & Component Library`) with Light & Dark `@Preview`s.
- [x] Upgrade `apps/android/app/src/main/java/com/anve/app/ui/common/ErrorNotice.kt` (`AnveErrorNotice`, `AnVeSectionHeader`, `AnVeStatusPill`) with Stitch Screen 52 tactile framing and previews.
- [x] Enhance `apps/android/app/src/main/java/com/anve/app/ui/brand/AnVeIllustrations.kt` (adding Stitch card framing `AnVeIllustratedBanner`, `AnVeSafeRadarScene`, and Canvas scenes) and sync `MISSING_ASSETS.md`.

## Phase 3: Stitch Blueprint Overhaul Across All Android Screens (Screens 53–58 + Secondary Flows)
- [x] **Shell & Account (`HomeScreen.kt` — Stitch Screens 53 & 57):** Redesign top brand header (`AnVeShellHeader`), pinned sharing status pill with pulse dot (`StitchLiveSharingPill`), tactile 4-tab bottom navigation dock (`HomeTab`: `MAP`, `CIRCLE`, `PLACES`, `HISTORY`) + SOS pill FAB, and Stitch Screen 57 `AccountContent` / `HomeContent` (privacy banner, `ProfileCard` with avatar edit badge & verified indicator, grouped device/session & diagnostics cards, single `toggleable` consent row preserved).
- [x] **Live Map & Audience (`LiveMapScreen.kt` — Stitch Screen 53):** Add Stitch tactile Safe-Home radar canvas fallback (`MapUnavailableCard` + `AnVeSafeRadarScene`) when Maps is unconfigured, `"Nhịp sống gia đình"` panel header, member cards, location metric cards, Journey card, and `AudienceSheet`.
- [x] **Family Circle & Invites (`CircleScreen.kt`, `EmailInvitationSheet.kt`, `InvitationDialog.kt`, `ReceivedInvitations.kt` — Stitch Screen 54):** Overhaul into Stitch's Hearth Status Card with progress indicator, member cards with avatar rings & role pills, invite code tickets, warm setup cards, and Stitch-styled modal sheets/dialogs.
- [x] **Safe Places (`PlacesScreen.kt` — Stitch Screen 55):** Add Safe Zone Overview card with smart-protection pill badge, category filter chips, `StitchCategoryIconBox` Safe Place cards with inner radius bar, vertical timeline arrival/departure event items (`StitchTimelineItem`), upgraded `PlaceDetail`, and upgraded `PlaceEditor`.
- [x] **Location History (`HistoryScreen.kt` — Stitch Screen 56):** Upgrade to Stitch's horizontal Member Avatar Pill Carousel, tactile Date Strip & Window Card, 3-column Journey Summary Card with `"Hành trình an toàn"` callout, Tactile Vertical Timeline (`StitchTimelineItem` with `.selectable()` rows), and retention card.
- [x] **SOS Emergency (`SosScreen.kt` — Stitch Screen 58):** Upgrade to Stitch's top readiness status pill, concentric-halo SOS centerpiece hero card, tactile recipient selection cards, 3-column National Emergency Hotline Grid (`113`, `114`, `115`), outbox & events timeline cards, notification readiness card, pinned send bar, and calming footer quote.
- [x] **Tracking Hub, Journey, Diagnostics, Auth & Avatar (`TrackingScreen.kt`, `JourneyScreen.kt`, `JourneyOptionsSheet.kt`, `DiagnosticsScreen.kt`, `SplashScreen.kt`, `SignInScreen.kt`, `AvatarSheet.kt`, `AvatarImage.kt`):** Synchronize hero cards, metric strips, vertical step timelines, tactile option sheets, editorial auth/splash surfaces, and studio avatar frame with Stitch Warm Tactile Minimalism + Light/Dark `@Preview`s.

## Phase 4: Server Landing Page, Contact Database/API & Notifications (Task 3)
- [x] OpenAPI Contract (`packages/api-contracts/openapi.yaml`) validated via `make contracts-lint`.
- [x] SQL Migration (`infra/migrations/0014_contacts.up.sql` & `0014_contacts.down.sql`).
- [x] Configuration (`.env.example` & `apps/server/internal/config/config.go`).
- [x] Contact Package & Notifiers (`apps/server/internal/contacts/`) + HTTP router wiring (`apps/server/internal/httpapi/` & `apps/server/cmd/api/main.go`).

## Phase 5: Verification & Self-Review
- [x] Run `make android-check` (`:app:testDebugUnitTest` + `:app:lintDebug` + `:app:assembleDebug` — 45 unit suites / 360 tests passed, 0 failures).
- [x] Run `ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:compileDebugAndroidTestKotlin` to verify all `androidTest` suites compile against the overhauled UI.
- [x] Run `make quality-check` across the entire repository and verify green status.
