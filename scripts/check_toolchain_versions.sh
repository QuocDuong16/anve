#!/usr/bin/env bash
#
# Toolchain version consistency guard.
#
# The Go release, the JDK and the Gradle distribution are pinned in mise.toml.
# A few of those pins are mirrored in files that other tools read directly
# (go.mod, the Gradle wrapper properties). This guard makes accidental drift
# fail fast instead of producing a build that differs between machines.
#
# Usage: bash scripts/check_toolchain_versions.sh

set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

violations=()

# Reads a version out of a file. The pattern must contain exactly one capture
# group that holds the value; the group is printed, or nothing when the file
# or the pattern does not match.
extract() {
    local file="$1" pattern="$2"
    [[ -f "$file" ]] || return 0
    sed -n -E "s#^.*${pattern}.*#\1#p" "$file" | head -n 1
}

fail() {
    violations+=("$1")
}

# ─── Go ───────────────────────────────────────────────────────────────────────
mise_go="$(extract mise.toml 'go = "([^"]+)"')"
gomod_go="$(extract apps/server/go.mod 'go ([0-9]+\.[0-9]+(\.[0-9]+)?)')"
dockerfile_go="$(extract apps/server/Dockerfile 'FROM golang:([0-9]+\.[0-9]+(\.[0-9]+)?)-alpine')"

if [[ -z "$mise_go" ]]; then
    fail 'mise.toml: missing go = "x.y.z" pin'
elif [[ -z "$gomod_go" ]]; then
    fail 'apps/server/go.mod: missing "go x.y.z" directive'
elif [[ "$mise_go" != "$gomod_go" ]]; then
    fail "Go pins disagree: mise.toml=$mise_go apps/server/go.mod=$gomod_go"
fi

# The builder image carries the Go release the binary is compiled with, so it
# has to agree with the toolchain pin. Dependabot can move the image tag, and
# this guard is what keeps that bump from silently changing the Go version the
# server is built with.
if [[ -z "$dockerfile_go" ]]; then
    fail 'apps/server/Dockerfile: missing golang base image pin'
elif [[ "$mise_go" != "$dockerfile_go" ]]; then
    fail "Go pins disagree: mise.toml=$mise_go apps/server/Dockerfile=$dockerfile_go"
fi

# ─── Java ─────────────────────────────────────────────────────────────────────
mise_java="$(extract mise.toml 'java = "([^"]+)"')"
jvm_target="$(extract apps/android/app/build.gradle.kts 'JvmTarget\.JVM_([0-9]+)')"

if [[ -z "$mise_java" ]]; then
    fail 'mise.toml: missing java = "..." pin'
elif [[ -z "$jvm_target" ]]; then
    fail 'apps/android/app/build.gradle.kts: missing jvmTarget setting'
else
    java_major="$(sed -E 's/.*-([0-9]+).*/\1/' <<<"$mise_java")"
    if [[ -n "$java_major" && "$java_major" -lt "$jvm_target" ]]; then
        fail "JDK major $java_major is older than the Android jvmTarget $jvm_target"
    fi
fi

# ─── Gradle ──────────────────────────────────────────────────────────────────
mise_gradle="$(extract mise.toml 'gradle = "([^"]+)"')"
wrapper_gradle="$(extract apps/android/gradle/wrapper/gradle-wrapper.properties \
    'gradle-([0-9]+\.[0-9]+(\.[0-9]+)?)-(bin|all)\.zip')"

if [[ -z "$mise_gradle" ]]; then
    fail 'mise.toml: missing gradle = "x.y.z" pin'
elif [[ -z "$wrapper_gradle" ]]; then
    fail 'apps/android/gradle/wrapper/gradle-wrapper.properties: missing or unreadable distributionUrl'
elif [[ "$mise_gradle" != "$wrapper_gradle" ]]; then
    fail "Gradle pins disagree: mise.toml=$mise_gradle gradle-wrapper.properties=$wrapper_gradle"
fi

# The wrapper must verify the distribution it downloads.
if ! grep -q '^distributionSha256Sum=' apps/android/gradle/wrapper/gradle-wrapper.properties 2>/dev/null; then
    fail 'apps/android/gradle/wrapper/gradle-wrapper.properties: missing distributionSha256Sum'
fi

# ─── Android SDK ─────────────────────────────────────────────────────────────
# CI installs the Android platform and build tools from the versions declared
# in the workflow, so they must match the ones the Gradle build asks for.
toml_compile_sdk="$(extract apps/android/gradle/libs.versions.toml 'compileSdk = "([^"]+)"')"
toml_build_tools="$(extract apps/android/gradle/libs.versions.toml 'buildTools = "([^"]+)"')"
workflow_compile_sdk="$(extract .github/workflows/android.yml 'COMPILE_SDK: "([^"]+)"')"
workflow_build_tools="$(extract .github/workflows/android.yml 'BUILD_TOOLS: "([^"]+)"')"
workflow_platform="$(extract .github/workflows/android.yml 'ANDROID_PLATFORM: "([^"]+)"')"

if [[ -z "$toml_compile_sdk" ]]; then
    fail 'apps/android/gradle/libs.versions.toml: missing compileSdk pin'
elif [[ -z "$workflow_compile_sdk" ]]; then
    fail '.github/workflows/android.yml: missing COMPILE_SDK pin'
elif [[ "$toml_compile_sdk" != "$workflow_compile_sdk" ]]; then
    fail "compileSdk pins disagree: libs.versions.toml=$toml_compile_sdk android.yml=$workflow_compile_sdk"
fi

if [[ -z "$toml_build_tools" ]]; then
    fail 'apps/android/gradle/libs.versions.toml: missing buildTools pin'
elif [[ -z "$workflow_build_tools" ]]; then
    fail '.github/workflows/android.yml: missing BUILD_TOOLS pin'
elif [[ "$toml_build_tools" != "$workflow_build_tools" ]]; then
    fail "buildTools pins disagree: libs.versions.toml=$toml_build_tools android.yml=$workflow_build_tools"
fi

# The platform package carries a minor suffix (android-37.0), so it cannot be
# derived from the API level alone. It still has to name the same API level.
if [[ -z "$workflow_platform" ]]; then
    fail '.github/workflows/android.yml: missing ANDROID_PLATFORM pin'
elif [[ "$workflow_platform" != "platforms;android-${toml_compile_sdk}."* ]]; then
    fail "ANDROID_PLATFORM '$workflow_platform' does not match compileSdk $toml_compile_sdk"
fi

# ─── Report ──────────────────────────────────────────────────────────────────
if ((${#violations[@]} > 0)); then
    echo "Toolchain version check failed:" >&2
    for violation in "${violations[@]}"; do
        echo "- $violation" >&2
    done
    exit 1
fi

echo "Toolchain version check passed."
