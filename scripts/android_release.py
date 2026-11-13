#!/usr/bin/env python3
"""Build/verify AABs; fixture signing never produces the production package."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
import check_android_privacy as privacy
import android_signed_smoke as signed_smoke

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "apps/android"
PUBLIC = ("ANVE_API_URL", "ANVE_GOOGLE_WEB_CLIENT_ID", "ANVE_MAPS_API_KEY", "ANVE_FIREBASE_APP_ID",
          "ANVE_FIREBASE_PROJECT_ID", "ANVE_FIREBASE_API_KEY", "ANVE_FIREBASE_SENDER_ID", "ANVE_VERSION_CODE", "ANVE_VERSION_NAME")
SIGNING = ("ANVE_KEYSTORE_FILE", "ANVE_KEYSTORE_PASSWORD", "ANVE_KEY_ALIAS", "ANVE_KEY_PASSWORD", "ANVE_SIGNING_CERT_SHA256", "ANVE_KEYSTORE_TYPE")
FLAGS = ("--no-daemon", "--no-configuration-cache", "--no-build-cache", "--console=plain")
PRIVACY_TASKS = (":app:writeReleasePrivacyDependencies", ":app:writeReleaseValidationPrivacyDependencies")


def redact(text, env):
    for key in ("ANVE_KEYSTORE_PASSWORD", "ANVE_KEY_PASSWORD", "ANVE_MAPS_API_KEY", "ANVE_FIREBASE_API_KEY"):
        if env.get(key):
            text = text.replace(env[key], "[redacted]")
    return text


def run(args, env, *, expected=0, visible=False, cwd=ANDROID):
    result = subprocess.run([str(arg) for arg in args], cwd=cwd, env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=1800)
    output = redact(result.stdout, env)
    if (expected == 0 and result.returncode != 0) or (expected != 0 and result.returncode == 0):
        print(output, flush=True)
        raise RuntimeError("Release check command did not return the expected outcome")
    if visible:
        print(output, flush=True)
    return result.stdout


def gradle(tasks, env, *, expected=0, extra=()):
    # Empty values deliberately block fallback to global Gradle properties.
    # Keep all inputs in the environment, never process-list command arguments.
    child = dict(env)
    for key in PUBLIC:
        child.setdefault(key, "")
    return run([ANDROID / "gradlew", *FLAGS, *extra, *tasks], child, expected=expected, visible=True)



def fixture_environment():
    allowed = ("PATH", "JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "GRADLE_USER_HOME", "HOME", "LANG", "LC_ALL", "TMPDIR")
    return {key: os.environ[key] for key in allowed if key in os.environ}


def java_tool(name, env):
    return Path(env["JAVA_HOME"]) / "bin" / name if env.get("JAVA_HOME") else name


def signing_fixture(directory, env):
    password = secrets.token_urlsafe(32)
    env.update({
        "ANVE_KEYSTORE_FILE": str(directory / "upload.p12"), "ANVE_KEYSTORE_PASSWORD": password,
        "ANVE_KEY_PASSWORD": password, "ANVE_KEY_ALIAS": "validation-upload", "ANVE_KEYSTORE_TYPE": "PKCS12",
    })
    run([java_tool("keytool", env), "-genkeypair", "-noprompt", "-storetype", "PKCS12", "-keystore", env["ANVE_KEYSTORE_FILE"],
         "-storepass:env", "ANVE_KEYSTORE_PASSWORD", "-keypass:env", "ANVE_KEY_PASSWORD", "-alias", env["ANVE_KEY_ALIAS"],
         "-keyalg", "RSA", "-keysize", "2048", "-validity", "365", "-dname", "CN=AnVe Release Validation"], env)
    os.chmod(env["ANVE_KEYSTORE_FILE"], 0o600)
    certificate = directory / "upload.der"
    run([java_tool("keytool", env), "-exportcert", "-keystore", env["ANVE_KEYSTORE_FILE"], "-storepass:env", "ANVE_KEYSTORE_PASSWORD",
         "-alias", env["ANVE_KEY_ALIAS"], "-file", certificate], env)
    env["ANVE_SIGNING_CERT_SHA256"] = hashlib.sha256(certificate.read_bytes()).hexdigest()


def bundletool(env):
    pin = json.loads((ROOT / "packages/tooling/bundletool.json").read_text())
    target = ANDROID / ".gradle/anve-tools" / f"bundletool-{pin['version']}.jar"
    target.parent.mkdir(parents=True, exist_ok=True)
    def valid():
        return target.is_file() and hashlib.sha256(target.read_bytes()).hexdigest() == pin["sha256"]
    if not valid():
        url = f"https://github.com/google/bundletool/releases/download/{pin['version']}/bundletool-all-{pin['version']}.jar"
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read(64 * 1024 * 1024 + 1)
        if len(data) > 64 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != pin["sha256"]:
            raise RuntimeError("bundletool download does not match the pinned digest")
        target.write_bytes(data)
    return [java_tool("java", env), "-jar", target]


def verify_signature(bundle, pin, env, *, expected=0):
    return run([java_tool("java", env), "-cp", ANDROID / "buildSrc/build/classes/java/main", "com.anve.buildlogic.VerifyBundle", bundle, pin],
               env, expected=expected)


def verify_bundle(bundle, variant, env):
    pin = env["ANVE_SIGNING_CERT_SHA256"].replace(":", "").lower()
    verify_signature(bundle, pin, env)
    with zipfile.ZipFile(bundle) as archive:
        symbol_entries = [
            entry for entry in archive.infolist()
            if entry.filename.startswith("BUNDLE-METADATA/com.android.tools.build.debugsymbols/")
            and entry.filename.endswith(".sym")
        ]
    if not symbol_entries or any(entry.file_size <= 0 for entry in symbol_entries):
        raise RuntimeError("AAB is missing non-empty native debug symbols")
    tool = bundletool(env)
    run([*tool, "validate", f"--bundle={bundle}"], env)
    # The XML contains client configuration. Parse it in memory, never echo it.
    manifest_text = run([*tool, "dump", "manifest", f"--bundle={bundle}", "--module=base"], env)
    document = ET.fromstring(manifest_text)
    android = "{http://schemas.android.com/apk/res/android}"
    expected_package = "com.anve.app" + (".releasevalidation" if variant == "releaseValidation" else "")
    expected_name = env["ANVE_VERSION_NAME"] + ("-validation" if variant == "releaseValidation" else "")
    if document.get("package") != expected_package or document.get(android + "versionCode") != env["ANVE_VERSION_CODE"] or document.get(android + "versionName") != expected_name:
        raise RuntimeError("AAB package/version does not match the requested release identity")
    application = document.find("application")
    if application is None or application.get(android + "debuggable", "false") != "false":
        raise RuntimeError("AAB must not be debuggable")
    privacy.check_bundle(manifest_text)
    sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    return {"sourceCommit": sha, "applicationId": expected_package, "versionCode": int(env["ANVE_VERSION_CODE"]),
            "versionName": expected_name, "variant": variant, "certificateSha256": pin,
            "bundleSha256": hashlib.sha256(bundle.read_bytes()).hexdigest(), "signatureVerified": True, "bundletoolValidated": True,
            "nativeDebugSymbolsVerified": True, "nativeDebugSymbolEntryCount": len(symbol_entries),
            "privacyInventorySha256": hashlib.sha256(privacy.BASELINE.read_bytes()).hexdigest(), "privacyManifestVerified": True}


def negative_archive_checks(bundle, env, directory):
    verify_signature(bundle, "0" * 64, env, expected=1)
    for kind in ("tampered", "unsigned", "extra-entry"):
        target = directory / f"{kind}.aab"
        changed = False
        with zipfile.ZipFile(bundle) as original, zipfile.ZipFile(target, "w") as modified:
            for item in original.infolist():
                if kind == "unsigned" and item.filename.upper().startswith("META-INF/"):
                    continue
                data = original.read(item)
                if kind == "tampered" and item.filename == "base/manifest/AndroidManifest.xml":
                    data += b"tamper"
                    changed = True
                modified.writestr(item, data)
            if kind == "extra-entry":
                modified.writestr("base/assets/unsigned.fixture", b"unsigned-entry")
        if kind == "tampered" and not changed:
            raise RuntimeError("Fixture does not contain the base manifest")
        verify_signature(target, env["ANVE_SIGNING_CERT_SHA256"], env, expected=1)


def publish_files(bundle, metadata, destination):
    destination.mkdir(parents=True, exist_ok=True)
    target = destination / f"anve-{metadata['variant']}-{metadata['versionCode']}-{metadata['bundleSha256']}.aab"
    shutil.copy2(bundle, target)
    target.with_suffix(".json").write_text(json.dumps(metadata, indent=2) + "\n")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write(f"artifact_dir={destination.resolve()}\n")
    print(f"Verified {metadata['variant']} AAB and digest/certificate manifest written to {destination}")


def check(quick, *, candidate=False):
    env = fixture_environment()
    gradle(["test"], env, extra=("-p", "buildSrc"))
    output = gradle([":app:bundleRelease"], env, expected=1)
    if "Release input validation failed" not in output or "ANVE_API_URL" not in output or "ANVE_KEYSTORE_FILE" not in output:
        raise RuntimeError("Production release did not fail closed on missing configuration")
    with tempfile.TemporaryDirectory(prefix="anve-release-key-") as scratch:
        directory = Path(scratch)
        signing_fixture(directory, env)
        env.update({"ANVE_API_URL": "https://release-validation.invalid/", "ANVE_GOOGLE_WEB_CLIENT_ID": "1234567890-validation.apps.googleusercontent.com",
                    "ANVE_MAPS_API_KEY": "validation-client-value", "ANVE_FIREBASE_APP_ID": "1:1234567890:android:abcdef0123456789",
                    "ANVE_FIREBASE_PROJECT_ID": "anve-release-validation", "ANVE_FIREBASE_API_KEY": "validation-client-value",
                    "ANVE_FIREBASE_SENDER_ID": "1234567890", "ANVE_VERSION_CODE": "2", "ANVE_VERSION_NAME": "0.3.0"})
        if quick:
            gradle([":app:verifyReleaseValidationInputs", ":app:processReleaseValidationResources", *PRIVACY_TASKS], env)
            privacy.check(candidate=candidate)
            print("Release configuration checks passed; no distribution AAB was produced.")
            return
        output = gradle([":app:bundleRelease"], env, expected=1)
        if "ANVE_API_URL must be an absolute HTTPS DNS URL" not in output:
            raise RuntimeError("Synthetic endpoint was not refused for the production package")
        for cache_flag in ("--configuration-cache", "--build-cache"):
            output = gradle([":app:bundleReleaseValidation"], env, expected=1, extra=(cache_flag,))
            if "Signing requires --no-configuration-cache --no-build-cache" not in output:
                raise RuntimeError("Release packaging was allowed with a signing cache enabled")
            if cache_flag == "--configuration-cache":
                poisoned = dict(env)
                for name in SIGNING:
                    poisoned[name] = "deliberately-invalid-signing-input"
                reused = gradle([":app:bundleReleaseValidation"], poisoned, expected=1, extra=(cache_flag,))
                if "Reusing configuration cache." not in reused or "Signing requires --no-configuration-cache --no-build-cache" not in reused:
                    raise RuntimeError("Cached release configuration depends on signing credential values")
        wrong_pin = dict(env, ANVE_SIGNING_CERT_SHA256="0" * 64)
        output = gradle([":app:verifyReleaseValidationInputs"], wrong_pin, expected=1)
        if "ANVE_SIGNING_CERT_SHA256 does not match" not in output:
            raise RuntimeError("Wrong signing certificate was not rejected by the Gradle gate")
        local_sdk = directory / "unreviewed.jar"
        local_sdk.write_bytes(b"")
        init_script = directory / "local-sdk.gradle"
        init_script.write_text("""gradle.projectsEvaluated {
    def app = gradle.rootProject.findProject(':app')
    if (app != null) {
        app.dependencies.add('implementation', app.files(System.getenv('ANVE_PRIVACY_FIXTURE_JAR')))
    }
}
""")
        output = gradle([PRIVACY_TASKS[0]], dict(env, ANVE_PRIVACY_FIXTURE_JAR=str(local_sdk)),
                        expected=1, extra=("--init-script", str(init_script)))
        if "Review the newly introduced local-file SDK" not in output:
            raise RuntimeError("A local-file SDK was silently omitted from the privacy inventory")
        gradle([":app:bundleReleaseValidation", ":app:assembleReleaseValidation", *PRIVACY_TASKS], env)
        privacy.check()
        bundle = ANDROID / "app/build/outputs/bundle/releaseValidation/app-releaseValidation.aab"
        metadata = verify_bundle(bundle, "releaseValidation", env)
        negative_archive_checks(bundle, env, directory)
        publish_files(bundle, metadata, ANDROID / "app/build/outputs/release-validation")
        apk = ANDROID / "app/build/outputs/apk/releaseValidation/app-releaseValidation.apk"
        apk_metadata = signed_smoke.verify_apk(apk, env["ANVE_SIGNING_CERT_SHA256"], env)
        signed_smoke.publish(apk, apk_metadata, ANDROID / "app/build/outputs/signed-validation-apk")
        print("Release validation passed: isolated package, ephemeral key, format/signature/pin and tamper rejection.")


def distribution(output):
    # Environment-only distribution inputs ensure the recorded values are the
    # ones used by Gradle, without putting key passwords on the command line.
    env = fixture_environment()
    env.update({key: os.environ[key] for key in (*PUBLIC, *SIGNING) if key in os.environ})
    before = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    if subprocess.run(["git", "diff", "--quiet", "HEAD", "--"], cwd=ROOT).returncode:
        raise RuntimeError("Distribution requires a clean tracked source tree")
    untracked = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard", "--", "apps/android", "scripts/android_release.py", "scripts/android_signed_smoke.py", "scripts/check_android_privacy.py", "packages/tooling/bundletool.json", "packages/privacy"], cwd=ROOT)
    if untracked.strip():
        raise RuntimeError("Distribution requires all release build sources to be tracked")
    gradle([":app:bundleRelease", *PRIVACY_TASKS], env)
    privacy.check("release")
    bundle = ANDROID / "app/build/outputs/bundle/release/app-release.aab"
    metadata = verify_bundle(bundle, "release", env)
    if metadata["sourceCommit"] != before or subprocess.run(["git", "diff", "--quiet", "HEAD", "--"], cwd=ROOT).returncode:
        raise RuntimeError("Source changed while building the distribution")
    publish_files(bundle, metadata, Path(output) if output else ANDROID / "app/build/outputs/distribution")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("check", "config-check", "review-candidate", "distribution"))
    parser.add_argument("--output")
    args = parser.parse_args()
    if args.mode == "distribution":
        distribution(args.output)
    else:
        check(quick=args.mode in ("config-check", "review-candidate"), candidate=args.mode == "review-candidate")


if __name__ == "__main__":
    main()
