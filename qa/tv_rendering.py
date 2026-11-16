"""Verify both rendering modes using the signed APK and TV remote navigation."""

import argparse
import json

from release_ui import Device, QA_OUTPUT, tv_highlight, verify_release_apk
import tv_navigation
from upgrade_seed import close_panels, open_app

LABEL = "界面渲染兼容"


def switch_value(device):
    for node in device.tree().iter("node"):
        if node.get("checkable") == "true" and any(
            child.get("text") == LABEL for child in node.iter("node")
        ):
            return node.get("checked") == "true"
    raise AssertionError(f"Missing switch: {LABEL}")


def open_settings(device):
    open_app(device)
    close_panels(device)
    device.more()
    device.menu("设置")
    assert "HTTP 代理" in tv_highlight(device), "Settings did not focus the proxy entry"


def set_compatibility(device, enabled):
    open_settings(device)
    if switch_value(device) != enabled:
        device.choose_with_remote(LABEL)
        assert switch_value(device) == enabled, "Switch state did not change"
        assert LABEL in tv_highlight(device), "Toggling the switch moved focus"
    device.key("KEYCODE_BACK")


def verify_settings(device):
    open_settings(device)
    device.key("KEYCODE_DPAD_CENTER")
    device.wait_for("IP 地址")
    device.key("KEYCODE_BACK")
    assert "HTTP 代理" in tv_highlight(device)
    device.choose_with_remote("检查应用更新")
    device.wait_for("关闭")
    device.key("KEYCODE_BACK")
    assert "检查应用更新" in tv_highlight(device)
    device.key("KEYCODE_DPAD_UP")
    assert "HTTP 代理" in tv_highlight(device)
    device.key("KEYCODE_BACK")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    device = Device(args.serial, "tv")
    entry, checksum = verify_release_apk(device)
    output = QA_OUTPUT / "tv-rendering"
    output.mkdir(parents=True, exist_ok=True)
    result = {
        "version": entry["versionName"], "version_code": entry["versionCode"], "sha256": checksum,
        "model": device.shell("getprop", "ro.product.model").strip(),
        "firmware": device.shell("getprop", "ro.build.fingerprint").strip(), "modes": {},
    }
    open_settings(device)
    original = switch_value(device)
    device.key("KEYCODE_BACK")
    try:
        for enabled in (False, True):
            mode = "software" if enabled else "hardware"
            set_compatibility(device, enabled)
            open_settings(device)
            assert switch_value(device) == enabled
            device.key("KEYCODE_BACK")
            verify_settings(device)
            tv_navigation.OUTPUT = output / mode
            tv_navigation.OUTPUT.mkdir(exist_ok=True)
            tv_navigation.verify_navigation(device)
            result["modes"][mode] = "passed"
    finally:
        try:
            set_compatibility(device, original)
        finally:
            (output / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result), flush=True)


if __name__ == "__main__":
    main()
