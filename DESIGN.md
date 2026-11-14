---
name: "AnVề"
description: "Native Android Material 3 for voluntary family location sharing"
colors:
  teal-900: "#00363A"
  teal-700: "#00505A"
  teal-400: "#2A8C95"
  teal-300: "#50B3B2"
  teal-100: "#B9E4E8"
  amber-800: "#65471F"
  amber-600: "#D98E32"
  amber-300: "#F2C48A"
  sand-50: "#FBF7F0"
  sand-200: "#EDE3D2"
  ink-900: "#11201F"
  ink-800: "#1B2D2C"
  ink-700: "#283C3B"
  ink-600: "#3C4F4E"
typography:
  headline:
    fontFamily: "FontFamily.Default"
  title:
    fontFamily: "FontFamily.Default"
  body:
    fontFamily: "FontFamily.Default"
  label:
    fontFamily: "FontFamily.Default"
spacing:
  dp-4: "4dp"
  dp-8: "8dp"
  dp-12: "12dp"
  dp-16: "16dp"
components:
  button-filled:
    backgroundColor: "{colors.teal-700}"
    textColor: "{colors.sand-50}"
  button-outlined:
    textColor: "{colors.teal-700}"
  button-text:
    textColor: "{colors.teal-700}"
  field-outlined:
    textColor: "{colors.ink-900}"
  navigation-bar:
    typography: "{typography.label}"
  card-outlined:
    backgroundColor: "{colors.sand-50}"
    textColor: "{colors.ink-900}"
  segmented-choice:
    backgroundColor: "{colors.teal-700}"
    textColor: "{colors.sand-50}"
---

# Design System: AnVề

## Overview

**Creative North Star: "AnVề native Material 3"**

AnVề uses a native Android Material 3 system: deep teal, warm amber, sand surfaces and ink text. The palette source describes a Vietnamese lacquerware influence (sơn mài); this is an existing palette rationale, not a new decorative direction. The default is the static brand scheme, with separate light and dark mappings. Material You is an explicit opt-in on supported Android versions.

The interface uses Vietnamese text and familiar Compose controls to make voluntary location sharing understandable. State, freshness and uncertainty must be legible without implying that a missing update proves harm. This document records the incumbent system; the Diagnostics overview/details composition belongs to its approved surface brief, not to every screen.

**Key Characteristics:**
- Native Material 3 components and semantic theme roles.
- Deep teal with warm amber, sand and ink neutrals.
- Vietnamese copy that distinguishes observed state from uncertainty.
- Privacy and user control expressed through explicit actions.

Extraction scope: `ui/theme/Color.kt`, `ui/theme/Theme.kt`, the current `DiagnosticsScreen.kt`, and the native controls in `HomeScreen.kt` and `SignInScreen.kt`. `PRODUCT.md` supplies durable language, privacy and trust constraints. No `Type.kt` or application typography/shapes override exists in the inspected theme directory. No new visual identity is proposed here.

## Colors

The frontmatter records the existing palette primitives; the table records their semantic use. Component frontmatter colors describe the static **light** scheme, not values to hardcode into Kotlin.

### Primary

Deep and mid teals carry the primary actions and their container/content roles. Pale teal supports informational containers. The numbered names retain the source palette identities.

### Secondary

Warm amber carries secondary and attention-related treatments. Amber is not an independent danger or safety score.

### Neutral

Sand provides warm light surfaces; ink provides text and dark surfaces. The dark scheme changes role assignments rather than inverting a screenshot.

| Material role | Light palette token | Dark palette token |
| --- | --- | --- |
| `primary` | `teal-700` | `teal-300` |
| `onPrimary` | `sand-50` | `teal-900` |
| `primaryContainer` | `teal-100` | `teal-700` |
| `onPrimaryContainer` | `teal-900` | `teal-100` |
| `secondary` | `amber-600` | `amber-300` |
| `onSecondary` | `ink-900` | `ink-900` |
| `secondaryContainer` | `amber-300` | `amber-800` |
| `onSecondaryContainer` | `ink-900` | `sand-50` |
| `background` | `sand-50` | `ink-900` |
| `onBackground` | `ink-900` | `sand-50` |
| `surface` | `sand-50` | `ink-900` |
| `onSurface` | `ink-900` | `sand-50` |
| `surfaceVariant` | `sand-200` | `ink-600` |
| `onSurfaceVariant` | `ink-600` | `sand-200` |

Surface containers use the sand ramp in light mode and the ink ramp in dark mode, including `ink-800` and `ink-700` for elevated dark surfaces. Roles omitted by `Theme.kt`, including error, outline and tertiary, inherit the installed Material 3 defaults. No bespoke values are asserted for them. `AnVeTheme` follows system dark mode by default and leaves dynamic color disabled unless explicitly requested.

The brand splash uses the theme surface as its canvas and semantic primary text
for the wordmark; it does not place text over a changing gradient. The
light/dark semantic foreground-container pairs are covered by
`ThemeContrastTest` at 4.5:1 for body text. Dark teal and amber container roles
are adjusted to keep readable text while retaining the existing brand hues.

**The Paired Roles Rule.** Choose a container and its corresponding content role together; verify the resulting pair in both themes. A role name alone is not proof of contrast.

**Emergency roles.** SOS surfaces use a dedicated emergency pair derived from the brand amber (`emergency`/`onEmergency` and `emergencyContainer`/`onEmergencyContainer` in `Theme.kt`), covered by `ThemeContrastTest` like the scheme pairs. Emergency is not `error`: amber urgency asks the user to act, while `error` stays reserved for failures. Stale map markers render in neutral gray for the same reason — the age of a location is not evidence of danger.

Diagnostics currently uses `primary`/`onPrimary` for its light app bar and selected segment, and `primaryContainer`/`onPrimaryContainer` in dark mode. Attention notices use `secondaryContainer`/`onSecondaryContainer` in light and `secondary`/`onSecondary` in dark. Dark text actions use `onSurface`. These are source-specific accessibility treatments, not a replacement global theme. Their final rendered review is separate from this extraction.

## Typography

The app inherits Compose Material 3 typography; `AnVeTheme` supplies no `Typography` override. `FontFamily.Default` in the frontmatter names the native Compose default, not a web font or a bundled brand face. Numeric type sizes, line heights and letter spacing are deliberately inherited from the dependency instead of being guessed or frozen here.

| Observed role | Native style | Use in the inspected screen |
| --- | --- | --- |
| Headline | `headlineSmall` | Current diagnostic explanation title; local `SemiBold` emphasis |
| Title | `titleMedium` | Section headers, notice heading and export action |
| Body | `bodyMedium` | Explanation and label/value rows |
| Supporting body | `bodySmall` | Caveats, report introduction and timestamps |
| Label | Component default | Native buttons and segmented choice |

The export report alone uses `FontFamily.Monospace` with `bodySmall` for selectable technical text. This is not the product’s body face. No custom display scale is established by the inspected screen.

**The Native Type Rule.** Use MaterialTheme.typography roles and Android sp scaling; do not copy mockup pixel measurements into Compose text.

## Layout

Use native layout, insets and scrolling. Observed spacing steps are recorded as dp measurements in the frontmatter, not new Kotlin spacing constants. Diagnostics uses a vertically scrolling `LazyColumn`, screen content padding and group gaps of `dp-16`, interior/header spacing of `dp-12`, row vertical spacing of `dp-8`, and a small title gap of `dp-4`. Its label/value rows share width and allow text to wrap. These measurements describe the inspected implementation; they do not establish every product screen’s composition.

The approved Diagnostics reference is `.impeccable/mocks/m7-diagnostics-c.png` with the overview/details contract in `.impeccable/surfaces/e-app-ui-diagnostics-diagnosticsscreen-kt-86f6aba0.md`. The reference is a mockup with synthetic data. It is not an emulator capture. Other layouts and expanded-width navigation require their own source and rendered verification; no breakpoint system was extracted.

**The Surface Boundary Rule.** Reuse the existing theme and native components. Keep a screen-specific composition in its surface brief unless it has been deliberately adopted across the product.

## Elevation & Depth

The inspected screen groups content through native outlined cards, tonal headers, dividers and a Material dialog. There are no custom shadow or elevation tokens in the inspected theme. Preserve Material component defaults and semantic surface relationships; this document does not assert a product-wide ban on shadows or invent a web shadow scale. Motion is likewise inherited from the native controls; no custom duration/easing scale was extracted.

## Shapes

`AnVeTheme` provides no custom `Shapes` override. Buttons, segmented choices, cards, fields and dialogs retain the installed Material 3 component shapes. Diagnostics uses `CircleShape` for its status icon surface. No application-specific numeric radius scale was extracted. Keep Android touch targets at least 48dp; the export action explicitly sets that minimum, while standard controls retain native minimum interaction sizing.

## Components

### Buttons

Use native `Button`, `OutlinedButton`, `TextButton` and `IconButton`. The filled action and outlined input are present in the sign-in form; Diagnostics uses text, outlined and icon actions. Keep labels as real text and disabled state on the control. Diagnostics disables refresh/export during loading and guards retry by the actual network, consent and queue state. Icon-only Back and refresh actions have spoken labels; decorative icons do not repeat adjacent text.

### Cards / Containers

Use `Card` and `OutlinedCard` rather than drawing controls into a raster. Diagnostics sections use surface-colored outlined cards, a surface-variant header and native dividers. Its row values use `onSurfaceVariant`. The notice is a state-specific tonal card with explicit text and an action. Semantic pairing and measured contrast take precedence over copying a single source assignment into every container.

### Inputs / Fields

`OutlinedTextField` is the incumbent field primitive. The inspected sign-in form supplies a native email keyboard, label, single-line behavior and an actual enabled state. Focus, selection, IME and error semantics belong to the native control; there is no extracted custom field CSS or focus ring.

### Navigation

The home screen uses `NavigationBar`/`NavigationBarItem` with Material vector icons and Vietnamese labels. This extraction records the current primitive, not a verification of its destination count or tablet behavior. Diagnostics has a top app bar with Back/refresh and a local `SingleChoiceSegmentedButtonRow`; it does not add a main navigation destination. Preserve system Back behavior.

**Top app bar treatment.** Which screen gets a top bar, and which color it wears, is a deliberate rule per surface kind:

- Secondary pages inside the home shell (Vị trí, Tài khoản, SOS) use the default surface `TopAppBar` with a title and a back action. They read as part of the app chrome, not as a destination of their own.
- Full-screen overlay flows (Hành trình, Chẩn đoán) use a `TopAppBar` in brand primary (light) / `primaryContainer` (dark). The color marks a focused, branded moment that covers the shell.
- SOS keeps its emergency amber treatment in the bottom bar (urgency ≠ failure); the top-bar rule does not recolor it, and amber urgency stays distinct from `error`.
- Every other screen is a tab inside the home shell and carries no top bar of its own unless actions are needed: History and Places use a surface `TopAppBar` inside their tab content for refresh and overflow actions.

### Report review

Diagnostics uses `AlertDialog` to disclose the report’s contents before the Android share chooser. The report is selectable and internally scrollable, with an observed maximum height of 240dp. Cancel and confirm remain native text actions. Exporting remains a deliberate user action and excludes coordinates and credentials under the product privacy contract.

**The Native State Rule.** Keep Material interaction semantics, disabled states, focus, pressed feedback and accessibility behavior on the real Compose control.

`.impeccable/design.json` adds native source references and static panel illustrations of these primitives. The panel is a documentation aid: HTML/CSS illustrations do not define Android dimensions, input behavior, accessibility or interaction states. Implement with Compose, then verify the rendered result separately.

## Do's and Don'ts

### Do:
- **Do** use semantic Material color roles and test their actual light/dark contrast.
- **Do** use native Compose controls, real Vietnamese text and Android dp/sp units.
- **Do** preserve explicit unknown, loading, offline and permission states.
- **Do** keep consent, system permissions, capture state and viewer access distinguishable.
- **Do** keep emergency (amber, for SOS urgency) distinct from `error` (failures), and render stale location data in neutral gray so old data never reads as danger.
- **Do** follow the configured comp-first workflow for every UI surface, including Android, and keep the approved surface reference scoped.
- **Do** verify rendered screens, enlarged text and interactions on an emulator or device.

### Don't:
- **Don't** interpret a stale location or missing update as evidence of a person’s safety or danger.
- **Don't** replace the incumbent palette, typography or native controls during a scoped refinement.
- **Don't** turn the Diagnostics overview/details layout or its synthetic example values into global rules or runtime evidence.
- **Don't** imply a report has been shared before the user reviews it and explicitly chooses to share.
- **Don't** present static documentation previews, source review, APK builds or JVM tests as emulator or physical-device validation.
