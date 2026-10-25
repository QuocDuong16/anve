# ADR 0003 — Android JSON: Moshi (reflection), not kotlinx-serialization

- **Status:** Accepted
- **Date:** 2026-10-25
- **Milestone:** M1

## Context

The Android app needs to talk to the Go API over JSON starting in M1. The
build runs AGP 9 with its **built-in Kotlin support**: the
`org.jetbrains.kotlin.android` plugin is deliberately not applied (see
`mem:tech_stack`), only `org.jetbrains.kotlin.plugin.compose`.

Candidate JSON stacks:

1. **kotlinx-serialization** — idiomatic, but requires the
   `org.jetbrains.kotlin.plugin.serialization` Gradle plugin, a compiler
   plugin that assumes a KGP-managed Kotlin compilation. On a module without
   KGP this is exactly the fragile corner (plugin/version coupling with
   AGP's built-in Kotlin) the repository has already been careful to avoid.
2. **Moshi with Kotlin reflection** (`moshi-kotlin`,
   `KotlinJsonAdapterFactory`) — plain JVM libraries, no compiler plugin, no
   codegen. Enforces Kotlin nullability at parse time (unlike Gson, which
   silently writes null into non-null fields).
3. **Gson** — no Kotlin awareness; a non-null Kotlin field can silently
   become null after parsing. Unacceptable for auth/location payloads.
4. Hand-rolled `org.json` parsing — no dependency risk but verbose and
   error-prone across the ~dozens of DTOs M1–M8 will accumulate.

## Decision

Use **Moshi with the Kotlin reflection adapter** for JSON on Android, behind
the Retrofit converter (`converter-moshi`). All API DTOs are Kotlin data
classes with explicit `@Json(name = "snake_case")` mappings to the OpenAPI
contract.

## Consequences

**Good**

- Zero Gradle-plugin coupling: the choice is compatible with AGP 9 built-in
  Kotlin and immune to Kotlin/AGP version interactions.
- Nullability is enforced: a missing field in a response fails loudly instead
  of producing a broken object.

**Bad**

- Reflection cost at first adapter construction (mitigated by sharing one
  `Moshi` instance app-wide) and `kotlin-reflect` on the classpath (~2 MB).

**Neutral**

- If the project later migrates back to KGP-managed Kotlin, switching to
  kotlinx-serialization is a mechanical change hidden behind the DTO layer.
