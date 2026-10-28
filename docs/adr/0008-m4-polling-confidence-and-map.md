# ADR 0008 — M4 conditional polling, confidence and map presentation

- **Status:** Accepted for the M4 MVP
- **Date:** 2026-10-27
- **Scope:** Latest-location distribution and Android Live Map. No history API,
  WebSocket, SSE, push notification or multi-instance event bus.

## Context

M4 needs near-real-time updates for a small family Circle, with per-request
authorization and reliable revocation. The API is a Go modular monolith backed
by PostgreSQL. The Android app already uses authenticated HTTPS requests. The
MVP has no deployed shared event broker or multi-replica realtime requirement.

## Decision

1. Android polls `GET /api/v1/locations/visible/latest` every 10 seconds while
   the Live Map screen is foreground and started. It sends the last ETag; an
   unchanged snapshot returns 304. Errors use bounded exponential backoff up
   to 60 seconds. Leaving the screen or backgrounding the app cancels polling.
2. Every poll re-runs the PostgreSQL authorization query. The server uses
   `Cache-Control: no-store, private` and `Vary: Authorization`; the ETag is
   viewer-specific because the response is derived from the authenticated
   viewer's grants. Per-point sample age advances on the client from the last
   server-computed age using a monotonic clock. Confidence changes invalidate
   the representation. The client never persists another member's location.
3. Conditional polling is near-real-time snapshot convergence, not server push
   or a guarantee of a particular latency. On an active network, the intended
   revocation observation bound is one poll interval; network failures are
   shown as disconnected and retain only the last authorized in-memory sample,
   visibly aging it to DELAYED/STALE. A screenshot or data already seen cannot
   be recalled.
4. Confidence is based on server time and the point's `recorded_at`: `FRESH`
   through 2 minutes, `DELAYED` through 15 minutes, then `STALE`. No sample or
   an excessive future timestamp is `UNKNOWN`. Client age can only downgrade
   the server classification. `OFFLINE` is reserved for a future trustworthy
   presence signal and is never inferred from missing GPS updates.
5. The Android map uses Maps Compose and is not instantiated when
   `ANVE_MAPS_API_KEY` is absent, so builds and non-map features remain usable.
   The key is a public application identifier, supplied outside source control
   and restricted in Google Cloud by package and signing certificate. Map
   rendering has not been device-verified without a configured key.

## Consequences

- Authorization and revocation remain simple: no long-lived stream or server
  subscriber can outlive a read request. There is no multi-replica event
  distribution claim.
- Ten-second polling has higher request overhead than SSE/WebSocket for a
  large audience, but is bounded by visible-screen lifecycle and 304 responses.
  Measure request volume and event-to-render delay before changing transport.
- Last-known location remains useful during a temporary network loss, but the
  UI labels its connection and age honestly. A 401/403 or account switch clears
  protected coordinates immediately.
- Google Maps rendering, key restrictions and device battery behavior require
  runtime/device validation. JVM tests and APK builds are not such evidence.

## Revisit when

The product needs materially lower update latency, polling load is measured to
be excessive, or multiple API replicas need server-pushed events. Any stream
must reuse ADR 0007 authorization and explicitly terminate future delivery on
grant, membership, consent or session revocation.
