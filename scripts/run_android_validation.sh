#!/usr/bin/env bash
set -Eeuo pipefail

# Collect partial captures before the action shuts down its emulator, while
# preserving the instrumentation failure as the workflow's actual outcome.
instrumentation_status=0
# AGP otherwise uninstalls the app and deletes its external files before pull.
timeout 900s make android-instrumentation \
    ANDROID_TEST_GRADLE_ARGS=-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
    || instrumentation_status=$?

captures_status=0
make android-ui-captures || captures_status=$?

if (( instrumentation_status != 0 )); then
    exit "$instrumentation_status"
fi
exit "$captures_status"
