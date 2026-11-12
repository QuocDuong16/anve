#!/usr/bin/env python3
"""Review drift in resolved release SDKs, merged manifest and source policies.

Only allowlisted manifest fields are retained. Client keys, raw XML, credential
values, provider configuration and user data never enter the review artifact.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "apps/android/app"
REPORTS = ANDROID / "build/outputs/privacy-review"
BASELINE = ROOT / "packages/privacy/android-release-baseline.json"
NS = "{http://schemas.android.com/apk/res/android}"
APP = "com.anve.app"
COMPONENTS = {"activity", "activity-alias", "service", "receiver", "provider"}


def enum_value(value, symbols):
    if value in symbols:
        return symbols[value]
    try:
        return int(value, 0)
    except (TypeError, ValueError):
        raise ValueError("Review the newly introduced manifest enum before exporting privacy fields") from None


def manifest_inventory(xml):
    root = ET.fromstring(xml)
    package = root.get("package")
    if package not in (APP, APP + ".releasevalidation"):
        raise ValueError("Unexpected release package in privacy review")
    application = root.find("application")
    if application is None:
        raise ValueError("Release manifest has no application")

    def identity(value):
        return value.replace(package + ".", "${applicationId}.") if value else value

    permissions = []
    declarations = []
    for child in root:
        if child.tag.startswith("uses-permission") or child.tag == "permission":
            fields = {key.removeprefix(NS): identity(value) for key, value in child.attrib.items() if key.startswith(NS)}
            fields["kind"] = child.tag
            if "protectionLevel" in fields:
                fields["protectionLevel"] = enum_value(fields["protectionLevel"],
                                                       {"normal": 0, "dangerous": 1, "signature": 2, "signatureOrSystem": 3})
            (declarations if child.tag == "permission" else permissions).append(fields)
    components = []
    for child in application:
        if child.tag not in COMPONENTS:
            continue
        fields = {key: child.get(NS + key) for key in (
            "name", "exported", "enabled", "permission", "readPermission", "writePermission",
            "grantUriPermissions", "foregroundServiceType", "directBootAware", "process",
        ) if child.get(NS + key) is not None}
        fields["kind"] = child.tag
        if "foregroundServiceType" in fields:
            fields["foregroundServiceType"] = enum_value(fields["foregroundServiceType"], {"none": 0, "location": 8})
        for key in ("enabled", "exported", "directBootAware", "grantUriPermissions"):
            if fields.get(key, "").startswith("@"):
                fields[key] = "@resource"
        for key in ("permission", "readPermission", "writePermission"):
            if key in fields:
                fields[key] = identity(fields[key])
        if child.get(NS + "authorities"):
            fields["authorities"] = identity(child.get(NS + "authorities"))
        fields["actions"] = sorted({e.get(NS + "name", "") for e in child.findall("intent-filter/action")})
        fields["categories"] = sorted({e.get(NS + "name", "") for e in child.findall("intent-filter/category")})
        # Data filters change external entry points. Persist only field names
        # and digests, since a malformed declaration might contain a token.
        fields["dataFilters"] = sorted(hashlib.sha256(json.dumps(sorted(e.attrib.items())).encode()).hexdigest()
                                      for e in child.findall("intent-filter/data"))
        fields["metadataNames"] = sorted(e.get(NS + "name", "") for e in child.findall("meta-data"))
        components.append(fields)
    if len({(c["kind"], c.get("name")) for c in components}) != len(components):
        raise ValueError("Ambiguous release component declarations")
    settings = {key: application.get(NS + key) for key in ("allowBackup", "debuggable", "usesCleartextTraffic")}
    settings["debuggable"] = settings["debuggable"] or "false"
    for key in ("networkSecurityConfig", "dataExtractionRules", "fullBackupContent"):
        # AAB manifests use compiled resource IDs rather than source names.
        settings[key + "Present"] = application.get(NS + key, "").startswith("@")
    flags = {}
    for child in application.findall("meta-data"):
        name = child.get(NS + "name", "")
        if name in ("firebase_messaging_auto_init_enabled", "firebase_analytics_collection_enabled",
                    "delivery_metrics_exported_to_big_query_enabled"):
            value = child.get(NS + "value")
            if value not in ("true", "false"):
                raise ValueError("Privacy metadata must use an explicit boolean")
            flags[name] = value
    settings["sdkFlags"] = flags
    settings["metadataNames"] = sorted(e.get(NS + "name", "") for e in application.findall("meta-data"))
    return {"permissions": sorted(permissions, key=lambda p: (p.get("name", ""), p["kind"])),
            "declaredPermissions": sorted(declarations, key=lambda p: p.get("name", "")),
            "application": settings,
            "components": sorted(components, key=lambda c: (c["kind"], c.get("name", "")))}


def read_modules(path):
    modules = path.read_text().splitlines()
    if not modules or any(not re.fullmatch(r"[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+-]+", item) for item in modules):
        raise ValueError("Invalid or empty resolved module inventory")
    return sorted(set(modules))


def source_policies():
    paths = []
    for variant in ("main", "release", "releaseValidation"):
        source = ANDROID / "src" / variant
        paths.extend(source.glob("AndroidManifest.xml"))
        paths.extend(source.glob("res/xml*/*.xml"))
    return {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(paths)}


def snapshot(variant="releaseValidation"):
    if variant not in ("release", "releaseValidation"):
        raise ValueError("Unknown release review variant")
    release = read_modules(REPORTS / "release-modules.txt")
    validation = read_modules(REPORTS / "releaseValidation-modules.txt")
    if release != validation:
        raise ValueError("Production and validation resolved SDK graphs differ; review coverage before release")
    title = variant[0].upper() + variant[1:]
    path = ANDROID / f"build/intermediates/merged_manifests/{variant}/process{title}Manifest/AndroidManifest.xml"
    return {"schemaVersion": 1, "modules": release, "manifest": manifest_inventory(path.read_text()),
            "sourcePolicies": source_policies()}


def require_review(actual, reviewed):
    if reviewed.get("schemaVersion") != 1 or actual.get("schemaVersion") != 1:
        raise ValueError("Unsupported Android privacy review schema")
    for key, expected_type in (("modules", list), ("manifest", dict), ("sourcePolicies", dict)):
        if not isinstance(actual.get(key), expected_type) or not actual[key] or not isinstance(reviewed.get(key), expected_type) or not reviewed[key]:
            raise ValueError("Incomplete Android privacy review inventory")
    changed = [key for key in ("modules", "manifest", "sourcePolicies") if actual.get(key) != reviewed.get(key)]
    if changed:
        # Do not echo unexpected manifest values into CI logs.
        raise ValueError("Android privacy review drift in " + ", ".join(changed) + "; review the candidate, SDK notes and declarations before updating the baseline")


def check(variant="releaseValidation", *, candidate=False):
    actual = snapshot(variant)
    REPORTS.mkdir(parents=True, exist_ok=True)
    (REPORTS / "candidate.json").write_text(json.dumps(actual, indent=2, sort_keys=True) + "\n")
    if candidate:
        print("Candidate written for source review; no baseline was changed and no release approval is implied.")
        return
    require_review(actual, json.loads(BASELINE.read_text()))
    print("Android privacy review matches: production/validation SDK graphs, merged manifest and source policy files.")


def check_bundle(xml):
    actual = manifest_inventory(xml)
    reviewed = json.loads(BASELINE.read_text())
    if actual != reviewed["manifest"]:
        raise ValueError("Packaged AAB manifest differs from the reviewed privacy inventory")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate", action="store_true", help="Write candidate only; never change the reviewed baseline")
    args = parser.parse_args()
    if args.candidate:
        check(candidate=True)
    else:
        check()


if __name__ == "__main__":
    main()
