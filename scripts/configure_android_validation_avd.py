#!/usr/bin/env python3
"""Match the CI emulator to the approved Diagnostics comparison frame."""

import os
from pathlib import Path


avd_root = Path(os.environ.get("ANDROID_AVD_HOME", Path.home() / ".android/avd"))
config_path = avd_root / "test.avd/config.ini"
# 841x1870 px at 327 dpi rounds to the recorded 412x915 dp phone viewport.
overrides = {
    "hw.lcd.width": "841",
    "hw.lcd.height": "1870",
    "hw.lcd.density": "327",
    "skin.name": "841x1870",
    "skin.path": "_no_skin",
    "showDeviceFrame": "no",
}
lines = config_path.read_text().splitlines()
lines = [line for line in lines if line.partition("=")[0].strip() not in overrides]
lines.extend(f"{key}={value}" for key, value in overrides.items())
config_path.write_text("\n".join(lines) + "\n")
print(f"Configured {config_path}: 841x1870 px, 327 dpi (412x915 dp rounded)")
