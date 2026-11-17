---
version: 1
slug: "nve-app-ui-onboarding-onboardingscreen-kt-01e44126"
primary_target: "apps/android/app/src/main/java/com/anve/app/ui/onboarding/OnboardingScreen.kt"
related_targets: ["apps/android/app/src/main/java/com/anve/app/ui/onboarding/OnboardingStepCards.kt","apps/android/app/src/main/java/com/anve/app/ui/onboarding/OnboardingViewModel.kt","apps/android/app/src/main/java/com/anve/app/ui/onboarding/OnboardingRouter.kt","apps/android/app/src/main/java/com/anve/app/ui/AnVeApp.kt"]
---

---
version: 1
slug: "com-anve-app-ui-onboarding-onboardingscreen-kt"
primary_target: "apps/android/app/src/main/java/com/anve/app/ui/onboarding/OnboardingScreen.kt"
related_targets: ["apps/android/app/src/main/java/com/anve/app/ui/onboarding/OnboardingStepCards.kt","apps/android/app/src/main/java/com/anve/app/ui/onboarding/OnboardingViewModel.kt","apps/android/app/src/main/java/com/anve/app/ui/onboarding/OnboardingRouter.kt","apps/android/app/src/main/java/com/anve/app/ui/AnVeApp.kt"]
---

# First-Run Onboarding (5 Steps)

Mode: Operate. Platform: native Android (Jetpack Compose Material 3). Warm Tactile Minimalism design system.
User approved Step 1–2 mockup (`.impeccable/mocks/onboarding/onboarding-step1-step2-family.png`) and Step 3–5 text wireframes on 2026-11-17, and approved v3 plan replacing email sending with 7-day link/code sharing (`https://<host>/invite#<CODE>` / `anve://invite/<CODE>`).

THESIS: Guide newly signed-in users without a family group through optional setup—family creation/joining, step-by-step permissions with a non-blocking "Để sau" escape hatch and separate default-OFF consent switch, audience grants, and first familiar place—without locking the app or starting tracking automatically.

OWN-WORLD: AnVề Warm Tactile Minimalism Material 3 tokens (`surface`, `primaryContainer` forest green pills, `secondaryContainer` soft blush prominent disclosure and reassurance cards), `Be Vietnam Pro`, native `Button`/`OutlinedCard`/`Switch`/`FilterChip`, existing `AnVeFamilySceneIllustration` and `AnVeHomeEmblem` Canvas vectors, zero shipping mockup rasters.

STORY: New account without a Circle lands on Step 1 (Guided vs Tự khám phá). Step 2 creates a family + 7-day single-use invite link via sharesheet (or joins via code/inbox + existing C confirmation dialog). Step 3 presents Google Play prominent disclosure above sequential Notifications, separate Consent switch + Foreground location, and Background Settings education—each skippable via "Để sau, tiếp tục". Step 4 shows waiting-for-members reassurance or per-member location grants, plus optional first Place creation. Step 5 summarizes true observed state before entering Home.

FIRST VIEWPORT: Top segmented 5-step progress bar + "Bước 1 / 5" pill, warm home illustration banner, headline "Chào mừng bạn đến với AnVề", subtitle, primary pill "Thiết lập từng bước (~2 phút)", secondary outlined pill "Tự khám phá ứng dụng".

FORM: Approved comp for Step 1–2 (adapted to link-only invite per v3 plan) and approved text wireframes for Step 3–5. Loading, offline, error/retry, unknown-result readback, permission denial/recovery, and account-change invalidation are explicit states. System Back navigates backward one step without mutations.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
