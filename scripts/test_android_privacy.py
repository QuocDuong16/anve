import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from check_android_privacy import manifest_inventory, read_modules, require_review, source_policies


def manifest(package="com.anve.app"):
    return f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}">
      <uses-permission android:name="android.permission.INTERNET"/>
      <uses-permission android:name="{package}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"/>
      <permission android:name="{package}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" android:protectionLevel="signature"/>
      <application android:allowBackup="true" android:networkSecurityConfig="@xml/network_security_config">
        <meta-data android:name="com.google.android.geo.API_KEY" android:value="private-client-value"/>
        <meta-data android:name="firebase_messaging_auto_init_enabled" android:value="false"/>
        <activity android:name="com.anve.app.MainActivity" android:exported="true">
          <intent-filter><action android:name="android.intent.action.MAIN"/></intent-filter>
        </activity>
        <provider android:name="sdk.Provider" android:exported="false" android:authorities="{package}.sdk"/>
        <service android:name="com.anve.app.tracking.TrackingService" android:exported="false" android:foregroundServiceType="location"/>
        <receiver android:name="sdk.Receiver" android:exported="true" android:permission="android.permission.DUMP"/>
      </application>
    </manifest>'''


class AndroidPrivacyTests(unittest.TestCase):
    def inventory(self, xml=None):
        return {"schemaVersion": 1, "modules": ["sdk:client:1.0"],
                "manifest": manifest_inventory(xml or manifest()), "sourcePolicies": {"policy.xml": "digest"}}

    def test_production_and_fixture_package_normalize_without_rewriting_class_names(self):
        production = self.inventory()
        require_review(self.inventory(manifest("com.anve.app.releasevalidation")), production)
        self.assertIn("com.anve.app.MainActivity", json.dumps(production))
        self.assertIn("${applicationId}.sdk", json.dumps(production))

    def test_new_permissions_exports_and_lost_component_guards_require_review(self):
        for old, new in (("android.permission.INTERNET", "android.permission.READ_CONTACTS"),
                         ('android:exported="false"', 'android:exported="true"'),
                         ('android:permission="android.permission.DUMP"', ''),
                         ('android:protectionLevel="signature"', 'android:protectionLevel="normal"'),
                         ('android:foregroundServiceType="location"', 'android:foregroundServiceType="0x00000000"'),
                         ('android:value="false"', 'android:value="true"')):
            with self.subTest(change=old), self.assertRaises(ValueError):
                require_review(self.inventory(manifest().replace(old, new)), self.inventory())

    def test_version_policy_and_missing_inventory_changes_cannot_pass(self):
        for key, replacement in (("modules", ["sdk:client:2.0"]), ("sourcePolicies", {"policy.xml": "changed"}),
                                 ("manifest", {}), ("modules", [])):
            actual = copy.deepcopy(self.inventory())
            actual[key] = replacement
            with self.subTest(key=key), self.assertRaises(ValueError):
                require_review(actual, self.inventory())
        with self.assertRaises(ValueError):
            require_review({"schemaVersion": 1}, {"schemaVersion": 1})

    def test_client_key_values_are_omitted_and_errors_do_not_echo_input(self):
        original = self.inventory()
        changed = self.inventory(manifest().replace("private-client-value", "another-private-client-value"))
        require_review(changed, original)
        self.assertNotIn("private-client-value", json.dumps(original))
        invalid = self.inventory(manifest().replace("android.permission.INTERNET", "private-input-marker"))
        with self.assertRaises(ValueError) as caught:
            require_review(invalid, original)
        self.assertNotIn("private-input-marker", str(caught.exception))

    def test_compiled_resource_references_and_xml_formatting_preserve_inventory(self):
        source = manifest().replace('android:name="sdk.Provider"', 'android:name="sdk.Provider" android:enabled="@bool/sdk_enabled"').replace('<action android:name="android.intent.action.MAIN"/>',
                                    '<action android:name="android.intent.action.MAIN"/><data android:scheme="anve" android:host="open"/>')
        compiled = source.replace('android:protectionLevel="signature"', 'android:protectionLevel="0x00000002"').replace(
            'android:foregroundServiceType="location"', 'android:foregroundServiceType="0x00000008"').replace(
            "@xml/network_security_config", "@ref/0x7f123456").replace("@bool/sdk_enabled", "@ref/0x7f000123").replace(
            '<data android:scheme="anve" android:host="open"/>', '<data android:host="open" android:scheme="anve" />\n')
        self.assertEqual(manifest_inventory(source), manifest_inventory(compiled))

    def test_unexpected_packages_and_unresolved_module_lists_are_rejected(self):
        with self.assertRaises(ValueError):
            manifest_inventory(manifest("other.app"))
        with tempfile.TemporaryDirectory() as scratch:
            path = Path(scratch) / "modules.txt"
            for value in ("", "FAILED", "sdk:client:1.0\nmissing-version"):
                path.write_text(value)
                with self.subTest(value=value), self.assertRaises(ValueError):
                    read_modules(path)

    def test_qualified_policy_overlays_are_included_and_debug_is_excluded(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            app = root / "app"
            for relative in ("main/res/xml/network_security_config.xml", "debug/res/xml/network_security_config.xml"):
                path = app / "src" / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("<policy/>")
            with patch("check_android_privacy.ROOT", root), patch("check_android_privacy.ANDROID", app):
                before = source_policies()
                overlay = app / "src/release/res/xml-v31/network_security_config.xml"
                overlay.parent.mkdir(parents=True)
                overlay.write_text("<changed-policy/>")
                after = source_policies()
                self.assertEqual(len(before), 1)
                self.assertEqual(len(after), 2)
                self.assertIn("app/src/release/res/xml-v31/network_security_config.xml", after)
                actual, reviewed = self.inventory(), self.inventory()
                actual["sourcePolicies"], reviewed["sourcePolicies"] = after, before
                with self.assertRaises(ValueError):
                    require_review(actual, reviewed)


if __name__ == "__main__":
    unittest.main()
