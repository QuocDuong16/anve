# ADR 0018 — Low-Battery Last Breath Alerts

- **Status:** Accepted product direction; contract, backend, Android engine and UI comp-first implementation in progress (physical-device battery/OEM validation pending).
- **Scope:** Voluntary low-battery last-location notifications at 15% and 5% during active sharing or Safe Journey, with explicit opt-in, silent charging recovery, and opaque FCM dispatch.

## Triggering, consent, and scope narrowing

Low-battery alerting is **two-layer opt-in (`low_battery_alerts_enabled`, default `FALSE`)**, separate from `location_sharing_enabled` and mutated with compare-and-set (`low_battery_alerts_version`) plus transactional `audit_log` rows. Alerts are emitted only while:
1. the device foreground tracking service is actively running (`activelyTracking`),
2. `location_sharing_enabled` is confirmed `TRUE`,
3. `low_battery_alerts_enabled` is confirmed `TRUE`, and
4. the device is **discharging** (`!isCharging`).

When the owner has an `ACTIVE` Safe Journey, the alert binds to `journey_id` (`scope = JOURNEY`) and recipients are narrowed exclusively to that journey's currently authorized `authorized_journey_recipients` (each referencing its exact `grant_id`). Outside an active Safe Journey (`scope = SHARING`), recipients are the owner's current `active_location_audience` grants.

## Thresholds, hysteresis, and anti-spam controls

- Fixed thresholds: **15%** (warning) and **5%** (critical last breath).
- Hysteresis rearm: rearm the 5% trigger only after battery rises to `>= 10%`; rearm both 15% and 5% only after rising to `>= 20%`.
- Boundary at start: if tracking starts when battery is already `<= 15%` and discharging, trigger once for the **lowest** satisfied threshold (`<= 5%` fires only 5% and marks 15% passed; `6..15%` fires 15%).
- Cooldown: PostgreSQL rejects a new distinct alert at the same threshold within **30 minutes** while an unresolved alert at that threshold exists (`LOW_BATTERY_ALERT_COOLDOWN`, HTTP 429).
- Client-chosen `eventId` UUID + SHA-256 `request_hash` make retries idempotent (exact retry returns HTTP 200; conflicting payload returns HTTP 409).

## Coordinate snapshot & offline TTL

The client captures a coordinate snapshot via `POST /api/v1/battery-alerts` using this fallback order:
1. Reuse the tracking engine's latest accepted fix if `< 30 seconds` old.
2. Otherwise request a single-shot `getCurrentLocation` (timeout `4 seconds`); if fresh, also submit it to the normal location pipeline so Live Map advances.
3. Otherwise fall back to the latest accepted fix if `<= 15 minutes` old (preserving original `recordedAt`).
4. Otherwise fail-open with `location = null` so the battery alert still reaches relatives.

Pending unsent alerts live **in-memory only** (max 2 slots: 15% and 5%) with a **15-minute TTL**. Pending alerts retry when connectivity returns within 15 minutes and cancel immediately on charging, battery recovery, tracking stop, consent OFF, preference OFF, account switch, or process death. The server rejects creates older than **15 minutes** (`BATTERY_ALERT_TOO_OLD`, HTTP 400) or more than 2 minutes in the future, and the notification worker expires queued FCM outbox items older than **15 minutes** so delayed stale low-battery pushes are never delivered hours after recharge.

## Opaque push, on-device place matching, and silent resolution

- FCM carries only `{"eventId": "<uuid>", "kind": "LOW_BATTERY"}` with `priority: "high"` (zero coordinates, battery percent, user names, or place labels).
- Recipient devices fetch `GET /api/v1/battery-alerts/{alertId}` (per-request PostgreSQL authorization rechecking membership, sharing epoch, consent, `visible_from`, `capture_generation`, retention, and journey grant when scoped) and privately match coordinates on-device against authorized `places` via `findMatchingPlace` — never sending coordinates automatically to the OS `Geocoder`.
- On 2xx server acceptance with at least one recipient, the sender sees a quiet local confirmation notification (`anve_battery_status`) for transparency.
- Live Map (`VisibleLocation.lowBatteryAlert`) surfaces the latest unresolved alert within **12 hours**; Safe Journey (`Journey.lowBatteryAlert`) surfaces the latest unresolved alert bound to `journey_id`.
- When battery recovers to `>= 20%`, the client sends silent `POST /api/v1/battery-alerts/resolve` (no FCM push) to set `resolved_at`, suppress queued notification work, and clear active badges (advancing Live Map ETags).

## Retention, history deletion, and evidence boundary

Alert records follow `LEAST(7 days, users.location_retention_days)` and `location_retention_watermark`. Inserting a `location_history_deletions` row or updating `users.location_retention_days` triggers an immediate delete of `low_battery_alerts` (retaining minimal UUID fences in `low_battery_alert_fences` until account deletion to block replay). Coordinates falling inside an existing deletion interval are stripped on creation.

**Evidence boundary:** Android 8+ forbids static manifest receivers for `ACTION_BATTERY_CHANGED`; battery monitoring runs only while the foreground `TrackingService` process is alive. Degraded batteries, sudden power-off before 5%, or OEM background kills can still prevent delivery. Unit, integration, and emulator tests validate software logic with synthetic states and cannot substitute for physical-device validation.
