#!/usr/bin/env python3
"""Runtime smoke for the isolated signed fixture, only on a disposable emulator."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import tomllib
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.anve.app.releasevalidation"
COMPONENT = PACKAGE + "/com.anve.app.MainActivity"
EVIDENCE = "ci-emulator-synthetic-signed-validation"
BASELINE = ROOT / "packages/privacy/android-release-baseline.json"


def command(args, *, env=None, timeout=60):
    # Tool output can include client config, UI state or platform diagnostics.
    # Retain only the explicitly constructed verification record, never raw output.
    result = subprocess.run([str(arg) for arg in args], env=env, cwd=ROOT,
                            capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"{Path(args[0]).name} command failed (exit {result.returncode})")
    return result.stdout


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_commit():
    return command(["git", "rev-parse", "HEAD"]).strip()


def sdk_tool(name, env):
    sdk = env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise RuntimeError("ANDROID_HOME or ANDROID_SDK_ROOT is required")
    version = tomllib.loads((ROOT / "apps/android/gradle/libs.versions.toml").read_text())["versions"]["buildTools"]
    tool = Path(sdk) / "build-tools" / version / name
    if not tool.is_file():
        raise RuntimeError(f"Pinned SDK tool is missing: {name}")
    return tool


def parse_apk_identity(badging, certificates, pin):
    # Build tools can print certificates per scheme (e.g. "V2 Signer:").
    # Use the verifier's explicit signer count and require every certificate
    # it prints to have the expected identity, including across schemes.
    count = re.findall(r"^Number of signers: (\d+)\s*$", certificates, re.M)
    matches = re.findall(r"^.+ certificate SHA-256 digest: ([0-9a-fA-F:]+)$", certificates, re.M)
    pin = pin.replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", pin) or count != ["1"] or not matches or any(value.replace(":", "").lower() != pin for value in matches):
        raise RuntimeError("APK signing certificate does not match the single expected signer")
    identities = re.findall(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'", badging, re.M)
    if identities != [(PACKAGE, "2", "0.3.0-validation")] or re.search(r"^application-debuggable(?:\s|$)", badging, re.M):
        raise RuntimeError("APK must be the non-debuggable isolated validation identity")
    return {"applicationId": PACKAGE, "versionCode": 2, "versionName": "0.3.0-validation", "certificateSha256": pin}


def verify_apk(apk, pin, env):
    certificates = command([sdk_tool("apksigner", env), "verify", "--verbose", "--print-certs", apk], env=env)
    badging = command([sdk_tool("aapt2", env), "dump", "badging", apk], env=env)
    identity = parse_apk_identity(badging, certificates, pin)
    return {**identity, "sourceCommit": source_commit(), "apkSha256": digest(apk),
            "privacyInventorySha256": digest(BASELINE), "variant": "releaseValidation",
            "signatureVerified": True, "evidenceTier": EVIDENCE}


def publish(apk, metadata, destination):
    destination.mkdir(parents=True, exist_ok=True)
    target = destination / f"anve-signed-validation-{metadata['apkSha256']}.apk"
    shutil.copy2(apk, target)
    target.with_suffix(".json").write_text(json.dumps(metadata, indent=2) + "\n")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write(f"smoke_apk_dir={destination.resolve()}\n")
    print("Verified signed validation APK and isolated verification record prepared.")


def verify_record(apk, metadata, env):
    expected_source = env.get("ANVE_SIGNED_SMOKE_SOURCE_COMMIT")
    if expected_source and (env.get("GITHUB_EVENT_NAME") != "workflow_dispatch" or not re.fullmatch(r"[0-9a-f]{40}", expected_source)):
        raise RuntimeError("Reusing a fixture requires a manual Actions run and an explicit full source commit")
    actual = verify_apk(apk, metadata.get("certificateSha256", ""), env)
    if expected_source:
        actual["sourceCommit"] = expected_source
    if metadata != actual:
        raise RuntimeError("APK record differs from the installed artifact, source or privacy inventory")
    return actual


def crash_probe(log):
    entries = []
    for line in log.splitlines():
        match = re.match(r"^[EF]/AndroidRuntime\(\s*(\d+)\):\s?(.*)$", line)
        if match:
            entries.append((match[1], match[2]))
    owners = {pid for pid, value in entries if value.startswith(f"Process: {PACKAGE}, PID:")}
    types, frames = [], []
    for pid, value in entries:
        if pid not in owners:
            continue
        exception = re.match(r"^(?:Caused by: )?([\w.$]+(?:Exception|Error))(?::|$)", value.strip())
        frame = re.match(r"^at ([\w.$<>]+)\([^():]*(?::(\d+))?\)", value.strip())
        if exception and exception[1] not in types:
            types.append(exception[1])
        if frame:
            frames.append({"method": frame[1], "line": int(frame[2]) if frame[2] else None})
    return {"ownedCrashFound": bool(owners), "exceptionTypes": types[:12], "frames": frames[:40]}


def select_emulator(devices, requested=None):
    targets = [line.split() for line in devices.splitlines() if line.strip() and not line.startswith("List of devices")]
    if requested:
        targets = [target for target in targets if target[0] == requested]
    if len(targets) != 1 or len(targets[0]) < 2 or targets[0][1] != "device" or not re.fullmatch(r"emulator-\d+", targets[0][0]):
        raise RuntimeError("Select exactly one online emulator; physical, offline and ambiguous targets are refused")
    return targets[0][0]


def signin_probe(xml, expected):
    document = ET.fromstring(xml)
    nodes = [node for node in document.iter("node") if node.get("package") == PACKAGE]
    labels = {node.get(field, "") for node in nodes for field in ("text", "content-desc")}
    surfaces = {"app" if node.get("package") == PACKAGE else
                "system" if node.get("package") == "com.android.systemui" else
                "permission" if node.get("package") in ("com.android.permissioncontroller", "com.google.android.permissioncontroller") else
                "launcher" if node.get("package") in ("com.android.launcher3", "com.google.android.apps.nexuslauncher") else "other"
                for node in document.iter("node")}
    return {"appNodeCount": len(nodes), "surfaces": sorted(surfaces),
            "nameVisible": expected["name"] in labels,
            "unavailableVisible": expected["unavailable"] in labels,
            "retryVisible": expected["retry"] in labels,
            "devLoginVisible": any(label in labels for label in expected["dev"]),
            "unavailableInsideMergedLabel": any(expected["unavailable"] in label for label in labels),
            "progressVisible": any(node.get("class") == "android.widget.ProgressBar" for node in nodes)}


def check_signin(xml, expected):
    probe = signin_probe(xml, expected)
    if probe["devLoginVisible"]:
        raise RuntimeError("Development sign-in is exposed in the signed fixture")
    return all(probe[field] for field in ("nameVisible", "unavailableVisible", "retryVisible"))


class Smoke:
    def __init__(self, serial, env):
        sdk = env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT")
        self.adb = str(Path(sdk) / "platform-tools/adb")
        self.serial = serial
        self.last_ui = {}

    def call(self, *args):
        return command([self.adb, "-s", self.serial, *args])

    def pid(self):
        # pidof returns exit 1 for an absent process; avoid hiding other adb failures.
        result = self.call("shell", f"pidof {PACKAGE} || true")
        return result.strip()

    def launch(self):
        output = self.call("shell", "am", "start", "-W", "-n", COMPONENT)
        if not re.search(r"^Status: ok\s*$", output, re.M):
            raise RuntimeError("Signed fixture activity did not start successfully")

    def assert_signin(self, expected):
        deadline = time.monotonic() + 45
        remote = "/data/local/tmp/anve-signed-smoke.xml"
        while time.monotonic() < deadline:
            if not self.pid():
                raise RuntimeError("Signed fixture process stopped before sign-in")
            self.call("shell", "uiautomator", "dump", remote)
            xml = self.call("exec-out", "cat", remote)
            self.last_ui = signin_probe(xml, expected)
            if check_signin(xml, expected):
                return
            time.sleep(1)
        raise RuntimeError("Signed fixture never reached its expected unavailable-provider sign-in state")

    def assert_tracking_absent(self):
        output = self.call("shell", "dumpsys", "activity", "services", PACKAGE)
        if re.search(r"ServiceRecord\{[^\n]*TrackingService", output):
            raise RuntimeError("Tracking service started without sign-in/consent")

    def capture_failure(self, output):
        # The Actions job provisions a fresh emulator and never authenticates.
        # Retain its synthetic startup screen, not a dump of arbitrary UI data.
        result = subprocess.run([self.adb, "-s", self.serial, "exec-out", "screencap", "-p"],
                                capture_output=True, timeout=15)
        if result.returncode or not result.stdout.startswith(b"\x89PNG\r\n\x1a\n"):
            return False
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(result.stdout)
        return True


def run_smoke(directory, output, env):
    if env.get("ANVE_SIGNED_SMOKE_DISPOSABLE") != "1":
        raise RuntimeError("Set ANVE_SIGNED_SMOKE_DISPOSABLE=1 only for a disposable emulator")
    apks = list(directory.glob("*.apk"))
    records = list(directory.glob("*.json"))
    if len(apks) != 1 or len(records) != 1 or records[0] != apks[0].with_suffix(".json"):
        raise RuntimeError("Expected exactly one APK and its matching verification record")
    metadata = json.loads(records[0].read_text())
    report = verify_record(apks[0], metadata, env)
    report["smokeRunnerCommit"] = source_commit()
    sdk = env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT")
    adb = Path(sdk) / "platform-tools/adb"
    serial = select_emulator(command([adb, "devices"]), env.get("ANDROID_SERIAL"))
    smoke = Smoke(serial, env)
    if smoke.call("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise RuntimeError("Target does not identify as an Android emulator")
    if smoke.call("shell", "pm", "list", "packages", PACKAGE).strip():
        raise RuntimeError("Validation package already exists; refuse to replace existing data")
    strings = {node.get("name"): node.text for node in ET.parse(ROOT / "apps/android/app/src/main/res/values/strings.xml").getroot()}
    expected = {"name": strings["app_name"], "unavailable": strings["signin_google_unavailable"],
                "retry": strings["signin_retry"], "dev": (strings["signin_dev_title"], strings["signin_dev_button"])}
    installed = False
    report.update({"success": False, "realProviderVerified": False, "physicalDeviceVerified": False,
                   "apiLevel": smoke.call("shell", "getprop", "ro.build.version.sdk").strip(),
                   "abi": smoke.call("shell", "getprop", "ro.product.cpu.abi").strip()})
    try:
        smoke.call("logcat", "-b", "crash", "-c")
        # Mark before installation so a timeout also attempts fixture cleanup.
        installed = True
        if "Success" not in smoke.call("install", "--no-streaming", str(apks[0].resolve())):
            raise RuntimeError("Validation APK installation failed")
        smoke.launch()
        report["stage"] = "fresh-launch"
        smoke.assert_signin(expected)
        smoke.assert_tracking_absent()
        report["freshLaunch"] = True
        smoke.call("shell", "input", "keyevent", "KEYCODE_HOME")
        smoke.launch()
        report["stage"] = "background-resume"
        smoke.assert_signin(expected)
        smoke.assert_tracking_absent()
        report["backgroundResume"] = True
        smoke.call("shell", "am", "force-stop", PACKAGE)
        if smoke.pid():
            raise RuntimeError("Fixture process did not stop before recreation")
        smoke.launch()
        report["stage"] = "process-recreation"
        smoke.assert_signin(expected)
        smoke.assert_tracking_absent()
        report.update({"processRecreation": True, "trackingNotStarted": True, "success": True})
    finally:
        try:
            if not report["success"] and env.get("GITHUB_ACTIONS") == "true":
                try:
                    report["crash"] = crash_probe(smoke.call("logcat", "-b", "crash", "-d", "-v", "brief"))
                except (OSError, RuntimeError, subprocess.TimeoutExpired):
                    report["crash"] = {"available": False}
                try:
                    report["syntheticFailureCapture"] = smoke.capture_failure(output.with_name("failure.png"))
                except (OSError, subprocess.TimeoutExpired):
                    report["syntheticFailureCapture"] = False
            if installed:
                smoke.call("shell", "am", "force-stop", PACKAGE)
                if "Success" not in smoke.call("uninstall", PACKAGE):
                    raise RuntimeError("Fixture cleanup failed")
                if smoke.call("shell", "pm", "list", "packages", PACKAGE).strip():
                    raise RuntimeError("Fixture remains installed after cleanup")
                smoke.call("shell", "rm", "-f", "/data/local/tmp/anve-signed-smoke.xml")
            report["cleanupPassed"] = True
        except (RuntimeError, subprocess.TimeoutExpired):
            report.update({"success": False, "cleanupPassed": False})
            raise
        finally:
            report["lastUiCheck"] = smoke.last_ui
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(json.dumps(report, indent=2) + "\n")
    print("Signed fixture passed fresh launch, background resume and process recreation on CI emulator.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact-dir", required=True, type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / "apps/android/app/build/outputs/signed-smoke/result.json")
    args = parser.parse_args()
    run_smoke(args.artifact_dir, args.output, os.environ)


if __name__ == "__main__":
    main()
