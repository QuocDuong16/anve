# Diagnostics direction provenance

The design authority is the existing AnVề Material 3 identity and the user's
selection of comp C (Tổng quan / Chi tiết). No new visual world was selected.

The original tool call, at 2026-11-11T04:15:44Z, was:

```text
impeccable build-phase start --direction 'user-pinned: M7-A diagnostics in existing AnVe Material 3 identity' --breakpoint 412x915 --artifact apps/android/app/src/main/java/com/anve/app/ui/diagnostics/DiagnosticsScreen.kt
```

Its recorded output began:

```text
BUILD-PHASE COMPS  comp (pending comp round)  direction seed:8cc5421842665ca5  breakpoint 412x915
```

Thus `seed:8cc5421842665ca5` in local build state is the alias the build tool
returned for a user-pinned direction. The source trail does not contain a
concept-seed roll for it, and `concept-seed --check-seed` returns UNKNOWN.
It must not be cited as proof of a rolled concept or a successful seed check.
The surface briefs now name the actual direction authority explicitly.

This provenance clarification does not close component/plan review, hero,
responsive or finish gates and is not an approval receipt.
