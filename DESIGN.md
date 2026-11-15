---
name: "AnVề"
description: "Warm tactile minimalism Material 3 design system for voluntary family location sharing"
colors:
  surface: "#fdf9f2"
  surface-dim: "#dddad3"
  surface-bright: "#fdf9f2"
  surface-container-lowest: "#ffffff"
  surface-container-low: "#f7f3ec"
  surface-container: "#f1ede7"
  surface-container-high: "#ebe8e1"
  surface-container-highest: "#e6e2db"
  on-surface: "#1c1c18"
  on-surface-variant: "#43483e"
  inverse-surface: "#31302c"
  inverse-on-surface: "#f4f0e9"
  outline: "#73796d"
  outline-variant: "#c3c8bb"
  surface-tint: "#446735"
  primary: "#3d602f"
  on-primary: "#ffffff"
  primary-container: "#557945"
  on-primary-container: "#dbffc6"
  inverse-primary: "#aad295"
  secondary: "#695b60"
  on-secondary: "#ffffff"
  secondary-container: "#f1dde4"
  on-secondary-container: "#6f6066"
  tertiary: "#30632a"
  on-tertiary: "#ffffff"
  tertiary-container: "#487c40"
  on-tertiary-container: "#dcffcf"
  error: "#ba1a1a"
  on-error: "#ffffff"
  error-container: "#ffdad6"
  on-error-container: "#93000a"
  primary-fixed: "#c5eeaf"
  primary-fixed-dim: "#aad295"
  on-primary-fixed: "#052100"
  on-primary-fixed-variant: "#2d4f20"
  secondary-fixed: "#f1dde4"
  secondary-fixed-dim: "#d4c2c8"
  on-secondary-fixed: "#23191e"
  on-secondary-fixed-variant: "#504349"
  tertiary-fixed: "#b7f2a9"
  tertiary-fixed-dim: "#9cd58f"
  on-tertiary-fixed: "#002201"
  on-tertiary-fixed-variant: "#1e511a"
  background: "#fdf9f2"
  on-background: "#1c1c18"
  surface-variant: "#e6e2db"
typography:
  display-lg:
    fontFamily: "Be Vietnam Pro"
    fontSize: "36px"
    fontWeight: "700"
    lineHeight: "44px"
    letterSpacing: "-0.02em"
  headline-lg:
    fontFamily: "Be Vietnam Pro"
    fontSize: "28px"
    fontWeight: "600"
    lineHeight: "36px"
    letterSpacing: "-0.01em"
  headline-md:
    fontFamily: "Be Vietnam Pro"
    fontSize: "22px"
    fontWeight: "600"
    lineHeight: "28px"
    letterSpacing: "-0.01em"
  headline-sm:
    fontFamily: "Be Vietnam Pro"
    fontSize: "18px"
    fontWeight: "600"
    lineHeight: "24px"
  title-lg:
    fontFamily: "Be Vietnam Pro"
    fontSize: "16px"
    fontWeight: "600"
    lineHeight: "22px"
  title-md:
    fontFamily: "Be Vietnam Pro"
    fontSize: "14px"
    fontWeight: "500"
    lineHeight: "20px"
  body-lg:
    fontFamily: "Be Vietnam Pro"
    fontSize: "16px"
    fontWeight: "400"
    lineHeight: "24px"
  body-md:
    fontFamily: "Be Vietnam Pro"
    fontSize: "14px"
    fontWeight: "400"
    lineHeight: "20px"
  body-sm:
    fontFamily: "Be Vietnam Pro"
    fontSize: "12px"
    fontWeight: "400"
    lineHeight: "16px"
  label-lg:
    fontFamily: "Be Vietnam Pro"
    fontSize: "14px"
    fontWeight: "600"
    lineHeight: "20px"
  label-md:
    fontFamily: "Be Vietnam Pro"
    fontSize: "12px"
    fontWeight: "600"
    lineHeight: "16px"
  label-sm:
    fontFamily: "Be Vietnam Pro"
    fontSize: "11px"
    fontWeight: "500"
    lineHeight: "14px"
    letterSpacing: "0.02em"
rounded:
  sm: "0.5rem"
  DEFAULT: "1rem"
  md: "1.5rem"
  lg: "2rem"
  xl: "3rem"
  full: "9999px"
spacing:
  gutter: "1rem"
  margin: "1rem"
  space-xs: "0.25rem"
  space-sm: "0.5rem"
  space-md: "0.75rem"
  space-lg: "1rem"
  space-xl: "1.5rem"
components:
  button-primary:
    backgroundColor: "{colors.primary-container}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.full}"
    height: "52dp"
  button-secondary:
    textColor: "{colors.primary-container}"
    rounded: "{rounded.full}"
  sos-fab:
    backgroundColor: "{colors.secondary-container}"
    textColor: "{colors.on-secondary-container}"
    rounded: "{rounded.DEFAULT}"
  card-default:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.on-surface}"
    rounded: "{rounded.md}"
  card-elevated:
    backgroundColor: "{colors.surface-container}"
    textColor: "{colors.on-surface}"
    rounded: "{rounded.md}"
  navigation-bar:
    backgroundColor: "{colors.surface-container-lowest}"
    typography: "{typography.label-md}"
---

# Design System: AnVề

> **Status note:** This document defines AnVề's design system (**Warm Tactile Minimalism**), synced across `apps/android` (`Color.kt`, `Theme.kt`, `Type.kt`, and UI screens) and the web landing page.

## Overview

**Creative North Star: "Warm Tactile Minimalism"**

This design system establishes a warm, grounded, and deeply protective atmosphere for voluntary family location sharing. Drawing from the quiet elegance of traditional Vietnamese lacquerware (*sơn mài*) and organic family hearth aesthetics, the system intentionally rejects the cold, punitive surveillance tropes common in tracking applications. Instead, it frames location awareness as an act of familial care and mutual reassurance (*"Đường về nhà, gần hơn mỗi ngày"*).

### Design Movement & Ethos
- **Warm Tactile Minimalism:** Spacious, breathing-room layouts anchored by soft, pill-shaped interactions, organic silhouettes, and comforting domestic metaphors (the lantern glow, the lighted home window, winding dusk pathways).
- **Voluntary & Transparent:** Privacy and agency are front-and-center. State, freshness, and uncertainty must be legible without implying that a missing update proves harm; absence of data or delayed timestamps is presented with calm neutrality rather than alarming red alerts or false emergencies.
- **Tone & Emotion:** Calm, protective, reassuring, domestic, and dependable. The visual rhythm feels gentle, human, and deliberate.
- **Native Android Material 3:** Built on Jetpack Compose Material 3 components and semantic color roles, using Vietnamese copy that distinguishes consent, system permissions, capture state, and viewer access.

## Colors

The palette is rooted in muted forest green grounds, soft blush secondary tones, and warm cream neutral grounds, creating an intimate daytime and welcoming presence that exudes warmth and clarity.

### Semantic Palette & Roles
- **Primary (`#3d602f` / `primary-container` `#557945` — Forest Green):** Primary user actions, active state confirmations, selected interactive items, and connection clarity. In light mode, it acts as a luminous, supportive anchor against warm cream surfaces.
- **Secondary (`#695b60` / `secondary-container` `#f1dde4`, `#F6E2E9` — Soft Blush):** Account badges, family territory highlights, journey milestones, warm domestic status indicators, and the distinctive SOS surface treatment.
- **Tertiary (`#30632a` / `tertiary-container` `#487c40` / `#5D9253` — Muted Forest):** Dedicated to grounding elements, structural contrast, and robust text or borders.
- **Neutral Grounds (`#fdf9f2` / `#FEFAF3` to `#1c1c18` / `#15370D` Organic Light):** Warm cream surfaces paired with deep organic ink for contrast and text elements representing safe, quiet space.
- **Surface Elevation Steps:**
  - Base / Lowest Surface: `#ffffff` (`surface-container-lowest`)
  - Surface / Card Level 1: `#fdf9f2` (`surface`) / `#FEFAF3` (warm cream card surface)
  - Elevated Surface Level 2 / Card Active: `#f7f3ec` (`surface-container-low`) to `#f1ede7` (`surface-container`) / `#F5EFE6`
  - High Contrast Stroke / Deep Ink: `#1c1c18` (`on-surface`) / `#15370D`

### Light, Dark & Contrast Rules
- **Material Color Roles:** The YAML frontmatter records the full light-mode Material 3 token set (`primary`, `on-primary`, `primary-container`, `on-primary-container`, `secondary`, `secondary-container`, `tertiary`, `surface` ramp, `outline`, `error`, and fixed roles). Dark theme must be designed as a first-class Material 3 scheme using semantic role remapping rather than a mechanical inversion.
- **The Paired Roles Rule:** Always choose a container and its corresponding content role together (`primaryContainer`/`onPrimaryContainer`, `secondaryContainer`/`onSecondaryContainer`, `errorContainer`/`onErrorContainer`) and verify light/dark contrast via `ThemeContrastTest` (>= 4.5:1 for body text).
- **Emergency vs. Error Separation:** `error` (`#ba1a1a` / `#ffdad6`) is strictly reserved for operation failures and destructive/conflict cautions. SOS urgency uses the warm blush emergency treatment (`#F6E2E9` / `secondary-container`) so urgent care asks the user to act without reading like a system crash. Stale map markers render in neutral gray so old location data is never misread as danger.
- **Splash & Wordmark:** The brand splash uses the theme surface as its canvas and semantic primary text for the wordmark, avoiding text over shifting gradients.

## Typography

Typography relies uniformly on **Be Vietnam Pro**, engineered to deliver pristine Vietnamese diacritics and warm, approachable open letterforms.

### Typography Hierarchy & Usage
- **Display & Headlines (`display-lg` 36px/700, `headline-lg` 28px/600, `headline-md` 22px/600, `headline-sm` 18px/600):** Reserved for screen headers (*"Địa điểm"*, *"Chia sẻ của tôi"*) and primary participant names. Never loud or shouty; set with balanced line heights to comfortably accommodate Vietnamese tone marks without clipping.
- **Section & Card Titles (`title-lg` 16px/600, `title-md` 14px/500):** Carries member locations, place names (*"Nhà"*, *"nhà trọ"*), and action labels with medium-to-semibold weights.
- **Body (`body-lg` 16px/400, `body-md` 14px/400, `body-sm` 12px/400):** Explanatory privacy statements, status descriptions, and reassurance messaging. Set at generous line heights (~1.45–1.5x) for stress-free legibility.
- **Labels (`label-lg` 14px/600, `label-md` 12px/600, `label-sm` 11px/500):** Used inside status pills, timestamps, navigation bar tabs, and button affordances.
- **Monospace Exception:** Technical export previews (e.g., Diagnostics report review) may use `FontFamily.Monospace` with `bodySmall` for selectable text.

**The Native Type Rule:** Map the `Be Vietnam Pro` scale to Compose `MaterialTheme.typography` roles using `sp` units so text respects Android system font-size settings (`font_scale 1.3`). Never hardcode fixed `px` sizes in Compose.

## Layout

The layout embraces generous breathing room and tactile vertical flow designed for one-handed mobile navigation.

### Layout Principles
- **Grid & Margins:** Uses a single-column fluid mobile canvas capped at `600dp` for large screens. Canvas side margins are fixed at `1rem` (`16dp`), creating an immersive frame that keeps touch targets safely within natural thumb reach.
- **Vertical Grouping Rhythm:**
  - Component Internal Padding: `space-md` (`12dp`) to `space-lg` (`16dp`) for nested rows and card bodies.
  - Section Gaps: `space-xl` (`24dp`) between distinct narrative groupings (e.g., Active Sharing Header, Places List, SOS button offset).
  - Compact Metadata Gaps: `space-xs` (`4dp`) to `space-sm` (`8dp`) between title lines, timestamps, and status chips.
- **Edge-to-Edge & Insets:** Honor status bar, navigation bar, display cutout, and IME insets, as well as the system Back gesture.
- **Touch Target Integrity:** Every clickable card, button, and navigation icon enforces a strict minimum `48dp × 48dp` target bounding box with at least `8dp` separation between adjacent targets.

## Elevation & Depth

Depth is established primarily through **tonal stacking** and subtle, tinted ambient radiance rather than harsh drop shadows.

### Layering Strategy
1. **Canvas Layer (Base):** Clean lowest surface `#ffffff` (`surface-container-lowest`) or warm cream `#fdf9f2` (`surface`), providing an undisturbed resting plane.
2. **Container Level 1 (Default Cards & Shelves):** Neutral tinted `#fdf9f2` / `#FEFAF3` with a crisp `1dp` hair-stroke outline (`outline-variant` / `#557945` where emphasized).
3. **Elevated Containers & Sheets (Level 2):** Floating bottom sheets, member selection dialogs, and active cards use `#f1ede7` / `#F5EFE6` (`surface-container`) with subtle tonal separation.
4. **Floating Action (SOS):** Warm glowing pill resting at the bottom-right corner (`16dp` above the navigation bar), cast with a warm-tinted shadow.

## Shapes

The shape system adopts a **Pill-shaped (level 3)** geometry to evoke organic softness, safety, and domestic warmth.

- **Primary Action Buttons & Interactive Pills:** Fully rounded (`rounded-full` / `9999px` / `CircleShape`) to invite instinctive touch without sharp corners.
- **Cards & Banners:** Large organic corner radius of `1.5rem` (`24dp`, `rounded.md`) or `1rem` (`16dp`, `rounded.DEFAULT`), softening container borders and framing domestic illustrations seamlessly.
- **Avatar Icons & Badges:** Pure circular boundaries (`CircleShape`) for profile photos, initial discs, and compass indicators.
- **Input Fields & Modal Sheets:** Top-radii of `1.5rem` (`24dp`) on bottom sheets and `1rem` (`16dp`) on text inputs (`OutlinedTextField`).

## Components

### Buttons & Quick Actions
- **Primary Pill Action:** Height `52dp`, background `#557945` (`primary-container`), text color `#ffffff` (`on-primary`, semibold). Fully rounded pill shape with centered icon + text.
- **Secondary / Ghost Pill:** Fully rounded pill with a `1dp` `#557945` outline, transparent fill, and `#557945` body text.
- **Emergency SOS FAB:** Distinctive blush button (`#F6E2E9` / `secondary-container`), bold uppercase typography, `16dp` rounded or pill-shaped, fixed at bottom-right `16dp` above the navigation bar. Tapping opens the SOS flow; it never triggers an automatic send.
- Use native `Button`, `OutlinedButton`, `TextButton`, and `IconButton` with real text, spoken labels for icon-only actions, and true disabled/loading states.

### Status Banner & Global Privacy Pill
- Fixed pill pinned below top navigation indicating sharing mode (e.g., *"Bạn đang chia sẻ với gia đình • Tạm dừng >"*), styled with a light cream outline and a luminous indicator.

### Cards & Narrative Illustration Containers
- **Content Cards:** Rounded `24dp` background in `#fdf9f2` / `#FEFAF3` paired with `1dp` border (`#557945` or `outline-variant`).
- **Hero Illustration Cards:** Warm theme-aware Compose `Canvas` illustrations (lighted homes, winding evening roads, family silhouettes, safe-zone pins) sit within the top half of cards with seamless color-bleed backgrounds, followed by supportive Vietnamese status typography and primary journey triggers. Illustrations clarify context without substituting for observed timestamps or states.

### Notices & Dialogs
- **The Failure Notice Rule:** Operation failures render as an `errorContainer` card through a shared error notice component wherever no extra affordances are needed. Bare `error`-colored text is reserved for destructive action labels and inline cautions about consent conflicts or device/permission state.
- **The Result Notice Rule:** One-shot confirmations (saved, created, revoked, joined) surface as a `Snackbar` on screens with a `SnackbarHost`; surfaces without their own host present results as a `secondaryContainer` card with an explicit dismiss. Persistent notices (consent pending/conflict, sync explanations) use the same card pattern.
- **Report Review Dialog:** Diagnostics uses a Material `AlertDialog` to disclose report contents before the Android share chooser (selectable, internally scrollable up to `240dp`), excluding coordinates and credentials per the privacy contract.

### Navigation Bar & Top App Bar
- **Navigation Bar:** Grounded light dock (`#ffffff` / `#fdf9f2`) featuring 4 to 5 core destinations (e.g., `Nhà`, `Gia đình`, `Địa điểm`, `Lịch sử`). Active items use a warm pill indicator (`#557945` with `#ffffff` icon) accompanied by semibold active label text. Inactive tabs rest at muted forest (`#557945` / `on-surface-variant`).
- **Top App Bar Treatment:**
  - Secondary pages inside the home shell (`Tài khoản`, `SOS`) use the default surface `TopAppBar` with a title and back action.
  - Full-screen overlay flows (`Hành trình`, `Chẩn đoán`) use a branded `TopAppBar` (`primary` in light / `primaryContainer` in dark).
  - Primary tabs carry no top bar of their own unless actions are needed (e.g., `Lịch sử` and `Địa điểm` for refresh/overflow).

### Switch & Selection Controls
- **Tactile Toggle Switches:** `32dp` height, track in soft cream (`#f1ede7` / `#F5EFE6`), active thumb in glowing forest primary (`#557945`). Keep native Material interaction semantics, focus, and accessibility behavior.

## Do's and Don'ts

### Do:
- **Do** use semantic Material 3 color roles (`MaterialTheme.colorScheme`) and test light/dark contrast via `ThemeContrastTest`.
- **Do** use `Be Vietnam Pro` mapped through `MaterialTheme.typography` with `sp` units and test at `font_scale 1.3`.
- **Do** use native Compose controls, real Vietnamese text, and `dp` spacing with `48dp` minimum touch targets.
- **Do** preserve explicit unknown, loading, offline, and permission states, keeping consent, system permissions, capture state, and viewer access distinguishable.
- **Do** keep SOS emergency treatment (soft blush) distinct from `error` (failures), and render stale location data in neutral gray so old data never reads as danger.
- **Do** follow the configured comp-first workflow (`.impeccable/config.json`) before implementing new screens or redesigns, and verify rendered builds on an emulator or device.

### Don't:
- **Don't** interpret a stale location or missing update as evidence of a person's safety or danger.
- **Don't** hardcode web `px`/`rem` values directly into Compose dimensions or bypass `MaterialTheme` roles.
- **Don't** imply a report has been shared before the user reviews it and explicitly chooses to share.
- **Don't** present static documentation previews, source review, APK builds, or JVM tests as emulator or physical-device validation.
