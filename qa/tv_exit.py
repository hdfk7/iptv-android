"""Check the TV exit hint and the two-press Back gesture on the signed Release."""

import argparse
import io
import json
import re
import time
from pathlib import Path

from PIL import Image

from release_ui import Device, PACKAGE, install_release


OUTPUT = Path("app/build/outputs/qa/tv-exit")


def hint_pixels(device, name):
    data = device.adb("exec-out", "screencap", "-p")
    (OUTPUT / f"{name}.png").write_bytes(data)
    with Image.open(io.BytesIO(data)) as source:
        width, height = source.size
        crop = source.convert("RGB").crop((width * 3 // 10, height * 8 // 10, width * 7 // 10, height * 97 // 100))
        count = sum(count for count, color in crop.getcolors(crop.width * crop.height) if color == (32, 38, 48))
        return count / (crop.width * crop.height)


def app_has_focus(device):
    focus = re.search(r"^\s*mCurrentFocus=(.+)$", device.shell("dumpsys", "window"), re.M)
    return focus is not None and PACKAGE in focus[1]


def verify(device):
    device.start()
    device.key("KEYCODE_INFO", "KEYCODE_BACK")
    baseline = hint_pixels(device, "fullscreen")
    device.key("KEYCODE_BACK")
    visible = hint_pixels(device, "hint-visible")
    assert visible - baseline > 0.03 and app_has_focus(device), "First Back did not show the hint in the app"
    time.sleep(2.1)
    expired = hint_pixels(device, "hint-expired")
    assert visible - expired > 0.03 and app_has_focus(device), "Hint did not expire while the app stayed open"
    device.key("KEYCODE_BACK", "KEYCODE_BACK")
    assert not app_has_focus(device), "Two Back presses did not exit the app"
    device.start()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    device = Device(args.serial, "tv")
    entry, checksum = install_release(device)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    result = {"serial": device.serial, "versionName": entry["versionName"],
              "versionCode": entry["versionCode"], "sha256": checksum, "passed": False}
    try:
        verify(device)
        result["passed"] = True
    finally:
        (OUTPUT / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("TV exit checks passed", flush=True)


if __name__ == "__main__":
    main()
