# ADR 0014 — Android release configuration and signed bundle gates

Status: Accepted for M8-B implementation; external release/provider evidence is separate.

Production release tasks require explicit HTTPS API configuration, Google/maps/
Firebase client configuration, version code/name and a valid upload signing key
matching an independently pinned SHA-256 certificate. No emulator/default or
placeholder endpoint becomes a distributable artifact. Client configuration is
syntax-checked, not remotely verified; version progression versus Play remains
an operator check. No store publication is added.

A separate minified releaseValidation build type uses application ID
com.anve.app.releasevalidation and an ephemeral key. It exercises real AAB
packaging/signature verification on CI without producing the production package.
Reserved synthetic hosts are permitted only there. Production release inputs
never use a test-mode bypass flag. Debug retains its existing local workflow.

Signing secrets use environment variables only, not Gradle command-line
properties. Signed builds run without configuration/build caches and with a
single-use Gradle process. If caches are enabled, configuration never queries
signing credentials and the release lifecycle guard refuses execution before
reading them. This also protects failed builds whose configuration Gradle may
still cache. Real keys stay outside the checkout on the release
runner, are never uploaded, and are cleaned on success/failure. The manual
workflow accepts only main, uses an explicit release environment and checks
required inputs before build. Required environment protections and signing
credentials are external prerequisites, not assumed configured.

Verify the AAB format/manifest with a digest-pinned bundletool; read all content
entries with Java JAR verification and require the pinned signer on each entry.
Record source commit, artifact digest, package/version and public certificate
fingerprint beside the verified AAB. This record is not an artifact attestation
or a claim of bit-for-bit reproducibility. CI also rejects tampered, unsigned and
wrong-certificate fixtures. No real service availability, physical-device
behavior, Play acceptance or owner release approval is inferred from fixtures.
