# ADR 0013 — Private, bounded HTTP observability

Status: Accepted (M8-A implementation; verification recorded separately).

## Context

M8 requires operational visibility without copying family/location data into
another store. Existing health/readiness probes and business audit records do
not provide aggregate HTTP traffic, response-class or duration measurements.
M7 field evidence and real Firebase delivery remain separate requirements.

## Decision

Use a process-local Prometheus registry, not the global registry. Register only
HTTP counts, duration histograms, in-flight requests, recovered panics and aborted
requests. No default process/Go collectors, runtime profiling or per-owner labels.
Only route templates registered by the application router can become labels;
unknown routes/methods and pre-routing refusals use bounded fallback groups.
The registry accepts at most 512 method/template pairs. No raw request values
or error/panic payloads enter metrics or the new completion log records.

Expose GET/HEAD /metrics on a separate opt-in literal-loopback listener only.
METRICS_LISTEN_ADDR is empty by default and rejects wildcards, non-loopback IPs,
DNS names and invalid ports. The public API never mounts /metrics. The handler
also checks the actual peer and Host are literal loopback addresses, ignores
forwarded-address headers, and rejects browser Origin requests. This prevents
DNS-rebinding/browser origins from reading the operator surface. Localhost
is an exposure restriction, not authentication: trusted host processes can read
these operational counts. Operators must not publish/reverse-proxy this listener;
remote scraping requires a separately designed authenticated transport.

Use the official Prometheus Go client with a private registry and fixed classic
histogram buckets. It handles serialization/concurrent collection. Metric state
is bounded by registered routes and fixed labels; counters reset on restart.
The scrape itself is not instrumented, and missing telemetry is not zero traffic.

Replace the generic panic logger on this router with a controlled recovery path:
never log panic values, stack traces, request bodies, URLs or headers. Before a
response is committed, return the existing generic INTERNAL JSON error with 500.
If a handler has already committed a response, abort the stream instead of
pretending to replace its status/body. Preserve net/http ErrAbortHandler behavior.
Completion logs use only canonical method/template, status, duration and bounded
boolean outcome flags. No caller-provided request ID is logged.

Bind both configured listeners before either serves; startup failure closes all
listeners. Stop both listeners on cancellation or an unexpected server stop,
using one bounded drain period and force-close after its deadline. Database
resources outlive HTTP draining; cancellation reaches background workers before
pool teardown.

## Consequences and limits

This measures total handler time, not durable ingestion latency or physical GPS,
notification delivery, battery/OEM behavior, production uptime or a verified SLO.
Operational log/collector retention is a deployment responsibility. No collector,
alert service, tracing backend, production deployment or public ingress is added.
Release signing, configuration gates, Play declarations and M7 field checks
remain separate readiness items. Verification must include label privacy/bounds,
concurrency, response/recovery behavior, listener isolation and shutdown.
