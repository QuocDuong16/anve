# ADR 0015 — Versioned Android privacy review inventory

Status: Accepted for the scoped M8-C implementation; owner/provider/submission
acceptance is separate.

An application manifest and direct dependency list omit SDK-added permissions,
components and transitive data paths. Maintain a reviewed JSON inventory of the
resolved production runtime graph, merged release manifest's allowlisted
privacy fields and non-debug source manifest/XML policy digests. Resolve the
production and isolated releaseValidation graphs through the Android variant
API; require equality so a fixture-only graph cannot conceal a release SDK.
Unresolved or unsupported project dependency graphs fail explicitly.

The shorter release configuration gate compares this inventory. The signed
AAB gate also compares the packaged manifest fields and records the baseline
digest beside the bundle digest/certificate. Client keys, raw XML and user
data are excluded from the inventory artifact. SDK module versions are review
inputs, not a count of collectors or a software-binary attestation.

New/removed permissions, component guards, SDK coordinates or source policy
files require reviewing the candidate and updating the Data Safety/permission
drafts as applicable in the same PR. `make android-privacy-candidate` only
writes a candidate; it never learns or approves the baseline. CI retains this
sanitized candidate even if the drift check fails.

Normalize application-ID-specific permission/authority names across the
production and fixture packages. Compiled AAB resource references differ from
source references, so the inventory records their presence or resource control;
it does not claim to resolve every compiled resource's semantics. Existing
source policies and provider/device checks remain separate evidence.

This is a review drift gate, not an exhaustive security audit, a semantic data-
flow scanner, provider telemetry verification, automatic Play form submission
or approval to publish. Keep unknown owner settings and real runtime/deployment
checks visible in the scoped review and release readiness documents.
