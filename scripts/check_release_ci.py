#!/usr/bin/env python3
"""Fail closed unless main's exact source commit passed both required workflows."""
import json
import os
import re
import urllib.request


def require_workflow(payload, sha, workflow):
    candidates = [run for run in payload.get("workflow_runs", [])
                  if run.get("head_sha") == sha and run.get("head_branch") == "main"
                  and run.get("event") == "push" and run.get("path") == ".github/workflows/" + workflow]
    if not candidates:
        raise RuntimeError("Required exact-commit CI workflow is missing")
    latest = max(candidates, key=lambda run: (run.get("created_at", ""), run.get("run_attempt", 1), run.get("id", 0)))
    if latest.get("status") != "completed" or latest.get("conclusion") != "success":
        raise RuntimeError("Latest required CI workflow has not completed successfully")


def main():
    repo, sha = os.environ.get("GITHUB_REPOSITORY", ""), os.environ.get("GITHUB_SHA", "")
    if os.environ.get("GITHUB_REF") != "refs/heads/main" or not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo) or not re.fullmatch(r"[a-f0-9]{40}", sha):
        raise RuntimeError("Release requires an exact main commit")
    token = os.environ.get("GH_TOKEN", "")
    if not token:
        raise RuntimeError("CI verification credential is missing")
    for workflow in ("go.yml", "android.yml"):
        url = f"https://api.github.com/repos/{repo}/actions/workflows/{workflow}/runs?head_sha={sha}&event=push&per_page=100"
        request = urllib.request.Request(url, headers={"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json"})
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                payload = json.load(response)
        except Exception:
            raise RuntimeError("Unable to verify required CI workflow") from None
        require_workflow(payload, sha, workflow)
    print("Exact main commit has successful Go and Android push workflows.")


if __name__ == "__main__":
    main()
