import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import android_signed_smoke as smoke


class SignedSmokeTests(unittest.TestCase):
    def test_disposable_opt_in_is_required_before_any_command(self):
        with patch.object(smoke, "command") as command, self.assertRaises(RuntimeError):
            smoke.run_smoke(Path("fixture"), Path("result.json"), {})
        command.assert_not_called()

    def test_apk_refuses_wrong_signer_identity_debug_and_multiple_signers(self):
        badging = f"package: name='{smoke.PACKAGE}' versionCode='2' versionName='0.3.0-validation'\n"
        cert = f"Number of signers: 1\nSigner #1 certificate SHA-256 digest: {'a' * 64}\n"
        smoke.parse_apk_identity(badging, cert, "a" * 64)
        for changed_badging, changed_cert, pin in (
            (badging.replace(smoke.PACKAGE, "com.anve.app"), cert, "a" * 64),
            (badging.replace("versionCode='2'", "versionCode='3'"), cert, "a" * 64),
            (badging.replace("0.3.0-validation", "0.3.0"), cert, "a" * 64),
            (badging + "application-debuggable\n", cert, "a" * 64),
            (badging, cert, "b" * 64),
            (badging, cert.replace("signers: 1", "signers: 2") + f"Signer #2 certificate SHA-256 digest: {'a' * 64}\n", "a" * 64),
            (badging, cert.replace("Number of signers: 1\n", ""), "a" * 64),
            (badging, "", "a" * 64),
        ):
            with self.subTest(badging=changed_badging, cert=changed_cert), self.assertRaises(RuntimeError):
                smoke.parse_apk_identity(changed_badging, changed_cert, pin)

    def test_sdk_scheme_labels_still_require_the_one_pinned_signer(self):
        badging = f"package: name='{smoke.PACKAGE}' versionCode='2' versionName='0.3.0-validation'\n"
        cert = f"Number of signers: 1\nV2 Signer: certificate SHA-256 digest: {'a' * 64}\n"
        smoke.parse_apk_identity(badging, cert, "a" * 64)
        smoke.parse_apk_identity(badging, cert + f"V3 Signer: certificate SHA-256 digest: {'a' * 64}\n", "a" * 64)
        with self.assertRaises(RuntimeError):
            smoke.parse_apk_identity(badging, cert + f"V3 Signer: certificate SHA-256 digest: {'b' * 64}\n", "a" * 64)

    def test_physical_offline_and_ambiguous_targets_are_refused(self):
        for devices in ("", "phone123\tdevice", "emulator-5554\toffline", "emulator-5554\tdevice\nemulator-5556\tdevice"):
            with self.subTest(devices=devices), self.assertRaises(RuntimeError):
                smoke.select_emulator("List of devices attached\n" + devices)
        self.assertEqual(smoke.select_emulator("emulator-5554\tdevice\nphone123\tdevice", "emulator-5554"), "emulator-5554")
        with self.assertRaises(RuntimeError):
            smoke.select_emulator("emulator-5554\tdevice", "phone123")

    def test_artifact_record_is_recomputed_not_trusted(self):
        record = {"certificateSha256": "a" * 64, "sourceCommit": "b" * 40, "apkSha256": "c" * 64}
        with patch.object(smoke, "verify_apk", return_value=record):
            self.assertEqual(smoke.verify_record(Path("fixture.apk"), record, {}), record)
            for field in ("sourceCommit", "apkSha256", "certificateSha256"):
                with self.subTest(field=field), self.assertRaises(RuntimeError):
                    smoke.verify_record(Path("fixture.apk"), dict(record, **{field: "wrong"}), {})

    def test_old_fixture_source_requires_manual_explicit_source(self):
        actual = {"certificateSha256": "a" * 64, "sourceCommit": "b" * 40}
        old = dict(actual, sourceCommit="c" * 40)
        with patch.object(smoke, "verify_apk", side_effect=lambda *args: dict(actual)):
            for env in ({}, {"ANVE_SIGNED_SMOKE_SOURCE_COMMIT": "c" * 40}, {"GITHUB_EVENT_NAME": "pull_request", "ANVE_SIGNED_SMOKE_SOURCE_COMMIT": "c" * 40}):
                with self.subTest(env=env), self.assertRaises(RuntimeError):
                    smoke.verify_record(Path("fixture.apk"), old, env)
            self.assertEqual(smoke.verify_record(Path("fixture.apk"), old, {"GITHUB_EVENT_NAME": "workflow_dispatch", "ANVE_SIGNED_SMOKE_SOURCE_COMMIT": "c" * 40}), old)

    def test_crash_probe_excludes_messages_and_foreign_processes(self):
        log = f'''E/AndroidRuntime( 123): FATAL EXCEPTION: main
E/AndroidRuntime( 123): Process: {smoke.PACKAGE}, PID: 123
E/AndroidRuntime( 123): java.lang.RuntimeException: private-account-value
E/AndroidRuntime( 123): Caused by: java.lang.IllegalStateException: private-token-value
E/AndroidRuntime( 123):     at com.anve.app.MainActivity.onCreate(MainActivity.kt:22)
E/AndroidRuntime( 456): Process: other.package, PID: 456
E/AndroidRuntime( 456): java.lang.SecurityException: unrelated
'''
        probe = smoke.crash_probe(log)
        self.assertTrue(probe["ownedCrashFound"])
        self.assertEqual(probe["exceptionTypes"], ["java.lang.RuntimeException", "java.lang.IllegalStateException"])
        self.assertEqual(probe["frames"], [{"method": "com.anve.app.MainActivity.onCreate", "line": 22}])
        self.assertNotIn("private", json.dumps(probe))
        self.assertNotIn("SecurityException", json.dumps(probe))

    def test_signin_requires_owned_nodes_and_rejects_dev_login(self):
        expected = {"name": "AnVề", "unavailable": "Unavailable", "retry": "Retry", "dev": ("Dev",)}
        nodes = ''.join(f'<node package="{smoke.PACKAGE}" text="{value}"/>' for value in ("AnVề", "Unavailable", "Retry"))
        xml = f"<hierarchy>{nodes}</hierarchy>"
        self.assertTrue(smoke.check_signin(xml, expected))
        self.assertFalse(smoke.check_signin(xml.replace(smoke.PACKAGE, "other.package"), expected))
        with self.assertRaises(RuntimeError):
            smoke.check_signin(xml.replace("</hierarchy>", f'<node package="{smoke.PACKAGE}" text="Dev"/></hierarchy>'), expected)

    def test_probe_retains_only_allowlisted_flags_not_ui_content(self):
        expected = {"name": "AnVề", "unavailable": "Unavailable", "retry": "Retry", "dev": ("Dev",)}
        xml = f'<hierarchy><node package="{smoke.PACKAGE}" text="private-ui-content"/><node package="unlisted.private.package" text="other-private-content"/></hierarchy>'
        probe = smoke.signin_probe(xml, expected)
        self.assertEqual(probe["appNodeCount"], 1)
        self.assertEqual(probe["surfaces"], ["app", "other"])
        self.assertNotIn("private", json.dumps(probe))

    def test_home_transition_waits_until_launcher_is_observed(self):
        device = smoke.Smoke("emulator-5554", {"ANDROID_HOME": "/sdk"})
        expected = {"name": "AnVề", "unavailable": "Unavailable", "retry": "Retry", "dev": ("Dev",)}
        snapshots = iter((f'<hierarchy><node package="{smoke.PACKAGE}" text="AnVề"/></hierarchy>',
                          '<hierarchy><node package="com.android.launcher3" text=""/></hierarchy>'))
        def call(*args):
            return next(snapshots) if args[0] == "exec-out" else ""
        with patch.object(device, "call", side_effect=call), patch.object(smoke.time, "sleep"):
            device.go_home(expected)
        self.assertEqual(device.last_ui["surfaces"], ["launcher"])
        self.assertEqual(device.last_ui["appNodeCount"], 0)

    def test_home_transition_does_not_silently_accept_the_app(self):
        device = smoke.Smoke("emulator-5554", {"ANDROID_HOME": "/sdk"})
        device.last_ui = {"surfaces": ["app"], "appNodeCount": 1}
        with patch.object(device, "call"), patch.object(device, "read_ui"), patch.object(smoke.time, "monotonic", side_effect=(0, 1, 16)), patch.object(smoke.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "Launcher"):
                device.go_home({})

    def test_preflight_never_touches_existing_package_or_non_emulator(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "fixture.apk").write_bytes(b"fixture")
            (directory / "fixture.json").write_text("{}")
            for qemu, packages in (("0", ""), ("1", "package:" + smoke.PACKAGE)):
                calls = []
                def call(*args):
                    calls.append(args)
                    return qemu if "getprop" in args else packages
                with patch.object(smoke, "verify_record", return_value={}), patch.object(smoke, "command", return_value="emulator-5554\tdevice"), patch.object(smoke.Smoke, "call", side_effect=call):
                    with self.assertRaises(RuntimeError):
                        smoke.run_smoke(directory, directory / "result.json", {"ANVE_SIGNED_SMOKE_DISPOSABLE": "1", "ANDROID_HOME": "/sdk"})
                self.assertFalse(any("install" in args or "uninstall" in args or "force-stop" in args for args in calls))

    def test_runtime_failure_still_cleans_and_records_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "fixture.apk").write_bytes(b"fixture")
            (directory / "fixture.json").write_text("{}")
            calls = []
            def call(*args):
                calls.append(args)
                if args[:2] == ("shell", "getprop"):
                    return "1"
                if args[0] in ("install", "uninstall"):
                    return "Success"
                return ""
            with patch.object(smoke, "source_commit", return_value="a" * 40), patch.object(smoke, "verify_record", return_value={}), patch.object(smoke, "command", return_value="emulator-5554\tdevice"), patch.object(smoke.Smoke, "call", side_effect=call), patch.object(smoke.Smoke, "launch", side_effect=RuntimeError("fixture launch failure")):
                with self.assertRaisesRegex(RuntimeError, "launch failure"):
                    smoke.run_smoke(directory, directory / "result.json", {"ANVE_SIGNED_SMOKE_DISPOSABLE": "1", "ANDROID_HOME": "/sdk"})
            report = json.loads((directory / "result.json").read_text())
            self.assertFalse(report["success"])
            self.assertTrue(report["cleanupPassed"])
            self.assertIn(("uninstall", smoke.PACKAGE), calls)


if __name__ == "__main__":
    unittest.main()
