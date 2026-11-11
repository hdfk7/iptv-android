"""Verify TV shortcuts and return paths using the current signed Release APK."""

import argparse
import hashlib
import json
import xml.etree.ElementTree as ET
from pathlib import Path

from release_ui import Device, find


PACKAGE = "cn.hdfk7.android.iptv"
APK_DIR = Path("app/build/outputs/apk/release")
OUTPUT = Path("app/build/outputs/qa/tv-navigation")


def verify_release(device):
    entry = json.loads((APK_DIR / "output-metadata.json").read_text())["elements"][0]
    apk = APK_DIR / entry["outputFile"]
    assert "Success" in device.adb("install", "-r", str(apk)).decode(), "Release installation failed"
    remote = device.shell("pm", "path", PACKAGE).strip().removeprefix("package:")
    checksum = hashlib.sha256(apk.read_bytes()).hexdigest()
    assert device.shell("sha256sum", remote).split()[0] == checksum, "Installed APK differs from local Release"
    package = device.shell("dumpsys", "package", PACKAGE)
    assert f"versionName={entry['versionName']}" in package
    assert f"versionCode={entry['versionCode']} " in package
    print(f"Verified Release {entry['versionName']} ({entry['versionCode']}): {checksum}", flush=True)
    return entry, checksum


def snapshot(device, name):
    root = device.tree()
    (OUTPUT / f"{name}.xml").write_bytes(ET.tostring(root, encoding="utf-8"))
    selected = [node for node in root.iter("node") if node.get("selected") == "true"]
    focused = selected or [node for node in root.iter("node") if node.get("focused") == "true"]
    label = [node.get("text") or node.get("content-desc") for parent in focused
             for node in parent.iter("node") if node.get("text") or node.get("content-desc")]
    print(f"{name}: {label}", flush=True)
    return root, label


def assert_fullscreen(root):
    assert find(root, "频道") is None, "Controls remain visible after BACK"
    assert find(root, "源管理") is None, "More menu remains visible after BACK"
    assert find(root, "节目单") is None, "Channel panel remains visible after BACK"


def verify_navigation(device):
    device.start()
    device.key("KEYCODE_INFO")
    device.key("KEYCODE_BACK")
    root, _ = snapshot(device, "01-fullscreen")
    assert_fullscreen(root)

    device.key("KEYCODE_MENU")
    root, focused = snapshot(device, "02-menu-from-fullscreen")
    assert find(root, "源管理") is not None, "MENU did not directly open More"
    assert "源管理" in focused, "The first More item did not receive focus"
    device.click("设置")
    device.key("KEYCODE_BACK")
    root, focused = snapshot(device, "02-settings-return")
    assert "设置" in focused, "Settings did not return focus to its More entry"
    device.key("KEYCODE_DPAD_LEFT")
    root, _ = snapshot(device, "02-settings-left-stays-in-menu")
    assert find(root, "源管理") is not None, "LEFT unexpectedly opened the channel panel after Settings"
    device.key("KEYCODE_BACK")
    root, _ = snapshot(device, "03-more-back")
    assert_fullscreen(root)

    device.key("KEYCODE_INFO")
    device.key("KEYCODE_MENU")
    root, _ = snapshot(device, "04-menu-from-controls")
    assert find(root, "源管理") is not None
    device.key("KEYCODE_BACK")

    device.key("KEYCODE_INFO")
    device.key("KEYCODE_DPAD_RIGHT", "KEYCODE_DPAD_RIGHT", "KEYCODE_DPAD_RIGHT")
    root, focused = snapshot(device, "05-bottom-more")
    assert "更多" in focused
    device.key("KEYCODE_DPAD_LEFT")
    root, focused = snapshot(device, "05-more-left-favorite")
    assert any("收藏" in label for label in focused), "More LEFT did not focus Favorite"
    assert find(root, "全部") is None, "LEFT from More unexpectedly opened the channel panel"
    device.key("KEYCODE_DPAD_LEFT")
    root, focused = snapshot(device, "05-favorite-left-play")
    assert any(label in ("播放", "暂停") for label in focused), "Favorite LEFT did not focus Playback"
    device.key("KEYCODE_DPAD_LEFT")
    root, focused = snapshot(device, "05-play-left-channels")
    assert "频道" in focused, "Playback LEFT did not focus Channels"
    device.key("KEYCODE_DPAD_LEFT")
    root, channel = snapshot(device, "05-left-from-controls")
    assert any(label.isdecimal() for label in channel), "LEFT did not focus a channel row"
    assert find(root, "节目单") is None, "The removed guide button is still visible"
    device.key("KEYCODE_BACK")
    root, _ = snapshot(device, "06-channels-back")
    assert_fullscreen(root)

    device.key("KEYCODE_DPAD_LEFT")
    root, restored = snapshot(device, "07-left-from-fullscreen")
    assert restored == channel, "Opening the channel panel lost its previous focus"

    for attempt in range(3):
        device.key("KEYCODE_BACK")
        device.key("KEYCODE_DPAD_LEFT")
        device.key("KEYCODE_DPAD_LEFT")
        root, tabs = snapshot(device, f"07-tabs-attempt-{attempt + 1}")
        assert tabs and not any(label.isdecimal() for label in tabs), "Second LEFT did not enter the group tabs"
        device.key("KEYCODE_DPAD_DOWN")
        root, restored = snapshot(device, f"07-tabs-return-{attempt + 1}")
        assert restored[:2] == channel[:2], "Tab DOWN did not focus the playing channel"

    if find(root, "全部") is not None:
        row_number = int(next(label for label in channel if label.isdecimal()))
        for _ in range(row_number - 1):
            device.key("KEYCODE_DPAD_UP")
        device.key("KEYCODE_DPAD_UP")
        root, tabs = snapshot(device, "07-top-skips-guide-button")
        assert tabs and "节目单" not in tabs and not any(label.isdecimal() for label in tabs)
        device.key("KEYCODE_DPAD_DOWN")
        root, restored = snapshot(device, "07-top-return")
        assert restored[:2] == channel[:2], "Tab DOWN did not return directly to the playing channel"

        for _ in range(8):
            device.key("KEYCODE_DPAD_DOWN")
        snapshot(device, "07-browse-middle")
        device.key("KEYCODE_DPAD_LEFT")
        device.key("KEYCODE_DPAD_RIGHT")
        device.key("KEYCODE_DPAD_DOWN")
        snapshot(device, "07-middle-switch-group")
        device.key("KEYCODE_DPAD_LEFT")
        device.key("KEYCODE_DPAD_LEFT")
        device.key("KEYCODE_DPAD_DOWN")
        root, restored = snapshot(device, "07-middle-return-playing")
        assert restored[:2] == channel[:2], "Switching tabs restored the browsing position instead of the playing channel"

    if any(label.startswith(("正在播", "接下来")) for label in channel):
        device.key("KEYCODE_DPAD_RIGHT")
        root, _ = snapshot(device, "08-guide")
        assert any("今日节目单" in node.get("text", "") for node in root.iter("node"))
        device.key("KEYCODE_DPAD_LEFT")
        root, restored = snapshot(device, "09-guide-back")
        assert restored == channel, "Closing the guide did not restore the channel row"

    device.key("KEYCODE_MENU")
    root, _ = snapshot(device, "10-menu-from-channels")
    assert find(root, "源管理") is not None
    device.key("KEYCODE_BACK")
    root, _ = snapshot(device, "11-final-fullscreen")
    assert_fullscreen(root)
    (OUTPUT / "fullscreen.png").write_bytes(device.adb("exec-out", "screencap", "-p"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    device = Device(args.serial, "tv")
    entry, checksum = verify_release(device)
    verify_navigation(device)
    (OUTPUT / "result.json").write_text(json.dumps({
        "serial": args.serial,
        "versionName": entry["versionName"],
        "versionCode": entry["versionCode"],
        "sha256": checksum,
        "passed": True,
    }, ensure_ascii=False, indent=2) + "\n")
    print("TV navigation checks passed", flush=True)


if __name__ == "__main__":
    main()
