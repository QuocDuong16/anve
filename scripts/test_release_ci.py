import os
import sys
import unittest
from android_release import run
from check_release_ci import require_workflow


class ReleaseCiTests(unittest.TestCase):
    def run_record(self, **overrides):
        return dict({"head_sha": "a" * 40, "head_branch": "main", "event": "push",
                     "path": ".github/workflows/android.yml", "status": "completed", "conclusion": "success",
                     "created_at": "2026-01-01T00:00:00Z", "run_attempt": 1, "id": 1}, **overrides)

    def test_exact_completed_commit_is_accepted(self):
        require_workflow({"workflow_runs": [self.run_record()]}, "a" * 40, "android.yml")

    def test_stale_wrong_or_incomplete_runs_cannot_authorize_release(self):
        for update in ({"head_sha": "b" * 40}, {"head_branch": "feature"}, {"event": "pull_request"}, {"event": "workflow_dispatch"},
                       {"status": "in_progress"}, {"conclusion": "failure"}, {"conclusion": "cancelled"},
                       {"path": ".github/workflows/other.yml"}):
            with self.subTest(update=update), self.assertRaises(RuntimeError):
                require_workflow({"workflow_runs": [self.run_record(**update)]}, "a" * 40, "android.yml")

    def test_new_failed_attempt_is_not_hidden_by_an_older_success(self):
        with self.assertRaises(RuntimeError):
            require_workflow({"workflow_runs": [self.run_record(), self.run_record(run_attempt=2, conclusion="failure")]}, "a" * 40, "android.yml")

    def test_missing_workflow_is_refused(self):
        with self.assertRaises(RuntimeError):
            require_workflow({"workflow_runs": []}, "a" * 40, "android.yml")


class AndroidReleaseOutputTests(unittest.TestCase):
    def test_manifest_output_excludes_stderr_warnings(self):
        command = [sys.executable, "-c", "import sys; print('<manifest/>'); print('WARNING: noisy stderr', file=sys.stderr)"]
        env = {"PATH": os.environ.get("PATH", "")}
        self.assertEqual(run(command, env, merge_stderr=False), "<manifest/>\n")
        self.assertIn("WARNING: noisy stderr", run(command, env))


if __name__ == "__main__":
    unittest.main()
