# Operator HTTP observability — M8-A

This is an opt-in local operator surface. It is not a location-sharing API,
a production monitoring deployment, or evidence of device/Firebase behavior.
Design: [ADR 0013](../adr/0013-private-http-observability.md).

## Enable and verify

For a local API process, keep the main API settings and add:

```bash
METRICS_LISTEN_ADDR=127.0.0.1:9090 make server-run
```

Without this variable, no metrics listener is opened. Literal loopback IPv4 or
IPv6 only; DNS names, wildcard binds, invalid ports and zoned IPv6 are refused.
The actual connection peer and Host must be loopback IP literals, and requests
with an Origin header are refused. Forwarded-address headers grant nothing.
Trusted processes on the host can read metrics: loopback is not authentication.
Do not publish, reverse-proxy or add a public Docker port for this listener.
A container's loopback is inside that container; no host scrape port is added.
Remote collection requires a separately designed authenticated transport.

On the same host/network namespace:

```bash
curl --fail http://127.0.0.1:9090/metrics
```

The main API's `/metrics` remains 404. The operator listener's business/probe
routes remain 404. GET and HEAD are supported, responses are private/no-store,
scrape concurrency is bounded to two and the handler timeout is five seconds.
A 403 indicates peer/Host/Origin rejection; 405 is the wrong method. A 503 is
not zero traffic. Keep `/healthz` for liveness and `/readyz` for DB readiness.
The configured listeners bind together and drain together; bind failure exits
rather than starting an API with a silently missing operator endpoint.

## Collected data and limits

| Metric | Meaning |
| --- | --- |
| `anve_http_requests_total{method,route,status_class}` | Completed requests by registered route template, fixed method and status class |
| `anve_http_request_duration_seconds{method,route}` | Classic histogram of complete handler duration in seconds |
| `anve_http_requests_in_flight` | Requests currently executing on the API |
| `anve_http_panics_total` | Handler panics; excludes intentional net/http aborts |
| `anve_http_aborted_requests_total` | Streams deliberately aborted after an intentional abort or panic after headers were committed |

Route registration is bounded to 512 application-owned method/template pairs.
Unknown routes, unmatched methods and refusals before endpoint selection are
grouped under `unmatched`; methods use the nine standard names or `OTHER`.
Status classes are `1xx` through `5xx`, with `none` for absent/unclassified status.
A failed stream may have a previously committed 2xx status: include the abort
counter when interpreting errors. No request-specific labels, exemplars,
coordinates, account/device/event IDs, URLs/queries, bodies or headers are read
into these metrics. No default Go/process collectors, profiling or trace export
is registered. Scraping does not count as API traffic.

The private Prometheus registry resets on process restart. Counters/histogram
vectors appear after their first observation; absence is not measured zero.
Collection across different metric families is not a transactional snapshot.
Label bounds protect memory from caller-supplied URLs/methods, not a guarantee
that traffic volume cannot be correlated by a trusted operator.

These are total HTTP timings. They are not durable location commit latency,
GPS accuracy/freshness, notification delivery/read time, battery overhead or
production availability. Ingestion's existing aggregate counters and business
audit records remain distinct sources. No latency/availability SLO is asserted.

The implementation uses the [official Prometheus Go client](https://github.com/prometheus/client_golang)
with a private registry and fixed classic buckets, following the documented
[metric exposition](https://prometheus.io/docs/instrumenting/exposition_formats/)
and [bounded label guidance](https://prometheus.io/docs/practices/instrumentation/#do-not-overuse-labels).

## Logs, recovery and operations

The new HTTP completion record allows only canonical method/route, status,
duration and panic/abort flags, plus the fixed service logger context. It does
not log caller-supplied request IDs, peer IPs, raw paths/queries, credentials,
request/response bodies, panic values or stack traces. Panic recovery sends the
generic INTERNAL JSON error before response commitment; after commitment it
aborts the stream rather than appending a false replacement response. Other
business/audit log schemas are separate; this is not a whole-codebase logging
privacy audit.

Metrics are process-memory state. Filesystem log retention, collector storage,
operator access and incident procedures belong to the deployment owner and
must be configured before production. No external collector or telemetry vendor
is contacted. Review route labels and privacy for every new instrumentation.

Before setting an error budget, collect a representative service baseline,
separate health probes from business traffic, identify counting/reset/abort
behavior and agree an availability definition and window with the operator.
Treat missing scrapes separately from a service response error. Production
thresholds and alert delivery need independent verification; M8-A adds no SLO
or alert promise.

## Verification scope

The Go suite covers malicious/unmatched values, bounded series, concurrent
requests/scrapes, literal peer/Host/Origin restrictions and forwarded-header
spoofing, no public metrics route, generic panic/abort behavior, exact completion
log fields, listener isolation, startup failure and bounded HTTP draining.
Use `make server-check`, `make quality-check` and
`make server-observability-smoke`; hosted Go CI includes the race detector and
runs that same binary smoke target. The smoke is also a dependency of the
full local quality target. The smoke launches the compiled API with
an explicit environment containing no database/provider credentials, checks
listener isolation and sanitized telemetry, then verifies a clean SIGTERM. Record current run/SHA in the PR; never equate a source check or
local test with a deployed collector, real Firebase or physical-device evidence.
