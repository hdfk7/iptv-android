"""Verify both rendering modes using the signed APK and TV remote navigation."""

import argparse
import json

from release_ui import Device, QA_OUTPUT, find, tv_highlight, verify_release_apk
import tv_navigation
from upgrade_seed import close_panels, open_app

LABEL = "界面渲染兼容"


def navigation_verified(result, serial, checksum):
    return result.get("passed") is True and result.get("serial") == serial and result.get("sha256") == checksum


def verify_critical_navigation(device):
    close_panels(device)
    device.key("KEYCODE_INFO")
    device.key("KEYCODE_DPAD_LEFT")
    root, focused = tv_navigation.snapshot(device, "critical-channels")
    assert any(label.isdecimal() for label in focused)
    device.key("KEYCODE_DPAD_RIGHT")
    root, _ = tv_navigation.snapshot(device, "critical-guide")
    assert any("今日节目单" in node.get("text", "") for node in root.iter("node"))
    device.key("KEYCODE_BACK")
    _, restored = tv_navigation.snapshot(device, "critical-guide-return")
    assert restored[:2] == focused[:2]
    device.key("KEYCODE_BACK")
    device.key("KEYCODE_MENU")
    root, focused = tv_navigation.snapshot(device, "critical-more")
    assert find(root, "源管理") is not None and "源管理" in focused
    device.key("KEYCODE_BACK")


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
    device.choose_with_remote("HTTP 代理", direction="KEYCODE_DPAD_UP")
    device.key("KEYCODE_BACK")
    assert "HTTP 代理" in tv_highlight(device)
    device.key("KEYCODE_BACK")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--full-navigation", action="store_true", help="Repeat the complete navigation in both modes")
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
    baseline = QA_OUTPUT / "tv-navigation" / "result.json"
    prior = json.loads(baseline.read_text(encoding="utf-8")) if baseline.is_file() else {}
    use_critical = not args.full_navigation and navigation_verified(prior, args.serial, checksum)
    result["navigationScope"] = "critical paths; same-APK full navigation already passed" if use_critical else "full navigation in both modes"
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
            if use_critical:
                verify_critical_navigation(device)
            else:
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
