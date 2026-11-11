#!/usr/bin/env bash
set -Eeuo pipefail

evidence_dir=apps/android/app/build/outputs/ui-validation
mkdir -p "$evidence_dir"
{
    git rev-parse HEAD
    adb -e shell wm size
    adb -e shell wm density
    adb -e shell settings get system font_scale
    echo 'Themes are fixture-controlled; dark-font130 captures use Compose fontScale=1.3.'
} > "$evidence_dir/emulator-metadata.txt"

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
