"""Check form layout on a device running the signed Release APK."""

import argparse
import hashlib
import io
import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path


PACKAGE = "cn.hdfk7.android.iptv"
APK_DIR = Path("app/build/outputs/apk/release")
QA_OUTPUT = Path(os.environ.get("IPTV_QA_OUTPUT", "app/build/outputs/qa"))
OUTPUT = QA_OUTPUT / "release-ui"
REMOTE_XML = "/data/local/tmp/iptv-qa-hierarchy.xml"


class Device:
    def __init__(self, serial, device_type):
        self.serial = serial
        self.device_type = device_type

    def adb(self, *args):
        return subprocess.check_output(["adb", "-s", self.serial, *args], timeout=60)

    def shell(self, *args):
        return self.adb("shell", *args).decode("utf-8")

    def input(self, *args):
        self.shell("input", *args)
        time.sleep(0.45)

    def key(self, *keys):
        self.input("keyevent", *keys)

    def tree(self):
        for attempt in range(3):
            try:
                self.shell("uiautomator", "dump", REMOTE_XML)
                return ET.fromstring(self.shell("cat", REMOTE_XML))
            except (subprocess.CalledProcessError, ET.ParseError):
                if attempt == 2:
                    raise
                time.sleep(0.5)

    def cleanup_hierarchy(self):
        self.shell("rm", "-f", REMOTE_XML)

    def tap(self, node):
        left, top, right, bottom = bounds(node)
        self.input("tap", str((left + right) // 2), str((top + bottom) // 2))

    def click(self, label, attr="text"):
        self.tap(self.wait_for(label, attr))

    def wait_for(self, label, attr="text", timeout=15):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            node = find(self.tree(), label, attr)
            if node is not None:
                return node
        raise AssertionError(f"Control not visible: {label}")

    def start(self):
        self.key("KEYCODE_HOME")
        if self.device_type == "phone":
            for direction in (1, 1, 1, -1, -1, -1, -1, -1, -1):
                root = self.tree()
                icon = find(root, "IPTV", "content-desc")
                if icon is not None:
                    self.tap(icon)
                    break
                left, top, right, bottom = bounds(root.find("node"))
                start, end = (right - 80, left + 80) if direction == 1 else (left + 80, right - 80)
                self.input("swipe", str(start), str((top + bottom) // 2), str(end), str((top + bottom) // 2), "350")
            else:
                raise AssertionError("IPTV was not found on the launcher pages")
        else:
            self.launcher_select("应用")
            self.launcher_select("IPTV")

    def launcher_select(self, label):
        for _ in range(24):
            root = self.tree()
            focused = next((node for node in root.iter("node") if node.get("focused") == "true"), None)
            target = find(root, label, "content-desc")
            if target is None:
                target = find(root, label)
            if focused is None or target is None:
                raise AssertionError(f"Launcher focus or entry missing: {label}")
            if find(focused, label, "content-desc") is not None or find(focused, label) is not None:
                self.key("KEYCODE_DPAD_CENTER")
                return
            left, top, right, bottom = bounds(focused)
            x1, y1, x2, y2 = bounds(target)
            x, y = (x1 + x2) // 2, (y1 + y2) // 2
            direction = ("UP" if y < top else "DOWN") if not top <= y <= bottom else ("LEFT" if x < left else "RIGHT")
            self.key("KEYCODE_DPAD_" + direction)
        raise AssertionError(f"Launcher entry is not reachable: {label}")

    def player_control(self, label):
        for _ in range(3):
            root = self.tree()
            button = find(root, label)
            if button is None:
                button = find(root, label, "content-desc")
            if button is not None:
                return button
            left, top, right, bottom = bounds(root.find("node"))
            # The middle of the screen may contain the Play button or status card.
            self.input("tap", str(left + (right - left) * 3 // 4), str(top + (bottom - top) // 3))
        raise AssertionError(f"Player control not visible: {label}")

    def more(self):
        if self.device_type == "tv":
            self.key("KEYCODE_MENU")
        elif find(self.tree(), "刷新频道") is None:
            self.tap(self.player_control("更多"))

    def menu(self, label):
        if self.device_type == "tv":
            self.choose_with_remote(label)
            return
        root = self.tree()
        node = find(root, label)
        if node is None and self.device_type == "phone":
            _, _, width, height = bounds(root.find("node"))
            x = str(int(width * 0.78))
            self.input("swipe", x, str(int(height * 0.69)), x, str(int(height * 0.26)), "350")
            node = find(self.tree(), label)
        if node is None:
            raise AssertionError(f"Menu entry not visible: {label}")
        self.tap(node)

    def choose_with_remote(self, label, direction="KEYCODE_DPAD_DOWN", limit=12):
        for attempt in range(limit):
            if label in tv_highlight(self):
                self.key("KEYCODE_DPAD_CENTER")
                return
            if attempt + 1 < limit:
                self.key(direction)
        raise AssertionError(f"Control not reachable by remote: {label}")

    def screenshot(self, mode, name):
        OUTPUT.mkdir(parents=True, exist_ok=True)
        (OUTPUT / f"{mode}-{name}.png").write_bytes(self.adb("exec-out", "screencap", "-p"))


def find(root, label, attr="text"):
    return next((node for node in root.iter("node") if node.get(attr) == label), None)


def bounds(node):
    return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))


def focus_color_present(screenshot, node):
    left, top, right, bottom = bounds(node)
    if right - left < 4 or bottom - top < 4:
        return False
    crop = screenshot.crop((left, top, right, bottom))
    colors = {(57, 86, 115), (76, 102, 128)}
    return sum(count for count, color in crop.getcolors(crop.width * crop.height)
               if color in colors) > crop.width * crop.height * 0.20


def tv_highlight(device, root=None, screenshot=None):
    from PIL import Image

    root = device.tree() if root is None else root
    screenshot = device.adb("exec-out", "screencap", "-p") if screenshot is None else screenshot
    with Image.open(io.BytesIO(screenshot)) as source:
        frame = source.convert("RGB")
        labels = []
        for node in root.iter("node"):
            label = node.get("text") or node.get("content-desc")
            if label and "://" not in label and focus_color_present(frame, node):
                labels.append(label)
        return labels


def check_form(root, fields):
    nodes = [find(root, field) for field in fields]
    actions = [find(root, label, "content-desc") for label in ("保存", "取消")]
    if any(node is None for node in nodes + actions):
        missing = [label for label, node in zip([*fields, "保存", "取消"], nodes + actions) if node is None]
        raise AssertionError(f"Form content clipped: {missing}")
    save, cancel = map(bounds, actions)
    _, _, width, height = bounds(root.find("node"))
    for left, top, right, bottom in (save, cancel):
        if not (0 <= left < right <= width and 0 <= top < bottom <= height):
            raise AssertionError("Form action is outside the screen")
        if bottom - top < (right - left) * 0.85:
            raise AssertionError("Form action is partially clipped")
    if max(bounds(node)[3] for node in nodes) > min(save[1], cancel[1]):
        raise AssertionError("Form fields overlap the footer")


def verify_forms(device, mode):
    from upgrade_seed import close_panels, open_app, source_manager

    open_app(device)
    source_manager(device)
    if device.device_type == "tv":
        device.choose_with_remote("添加源")
    else:
        device.click("添加源", "content-desc")
    check_form(device.tree(), ("源名称", "播放列表地址", "XMLTV 地址（选填）"))
    device.screenshot(mode, "add-source")
    if device.device_type == "tv":
        device.key("KEYCODE_BACK")
        assert "添加源" in tv_highlight(device), "TV add form did not restore the Add focus"
    else:
        device.click("取消", "content-desc")
    close_panels(device)
    device.more()
    device.menu("设置")
    if device.device_type == "tv":
        assert "HTTP 代理" in tv_highlight(device), "TV settings did not focus HTTP 代理"
        device.key("KEYCODE_DPAD_CENTER")
    else:
        device.click("HTTP 代理")
    check_form(device.tree(), ("IP 地址", "端口", "用户名（可选）", "密码"))
    device.screenshot(mode, "proxy")
    device.key("KEYCODE_BACK")
    if device.device_type == "tv":
        assert "HTTP 代理" in tv_highlight(device), "TV proxy did not restore the settings focus"
    device.key("KEYCODE_BACK")


def release_artifact():
    entry = json.loads((APK_DIR / "output-metadata.json").read_text(encoding="utf-8"))["elements"][0]
    return entry, APK_DIR / entry["outputFile"]


def verify_installed(device, apk, entry):
    paths = device.shell("pm", "path", PACKAGE).splitlines()
    if not paths or not paths[0].startswith("package:"):
        raise AssertionError("The IPTV package is missing")
    installed = paths[0].removeprefix("package:")
    local_hash = hashlib.sha256(apk.read_bytes()).hexdigest()
    if device.shell("sha256sum", installed).split()[0] != local_hash:
        raise AssertionError("Installed APK does not match the Release build")
    package = device.shell("dumpsys", "package", PACKAGE)
    if not re.search(rf"\bversionName={re.escape(entry['versionName'])}\s", package):
        raise AssertionError("Installed versionName does not match the Release build")
    if not re.search(rf"\bversionCode={entry['versionCode']}\s", package):
        raise AssertionError("Installed versionCode does not match the Release build")
    print(f"{device.serial} {entry['versionName']} ({entry['versionCode']}) {local_hash}", flush=True)
    return entry, local_hash


def verify_release_apk(device):
    if (QA_OUTPUT / ".iptv-qa-run").is_file():
        checkpoint = QA_OUTPUT / "local-upgrade" / "paired-run.json"
        assert checkpoint.is_file(), "Complete the paired upgrade before running Release regression"
        state = json.loads(checkpoint.read_text(encoding="utf-8"))
        assert all(state["devices"][kind].get("passed") for kind in ("phone", "tv"))
        assert state["identity"][device.device_type] == device.serial
        entry = state["identity"]["to"]
        apk = Path(os.environ["IPTV_QA_APK"]) if os.environ.get("IPTV_QA_APK") else release_artifact()[1]
        assert state["identity"]["candidateSha256"] == hashlib.sha256(apk.read_bytes()).hexdigest()
    else:
        entry, apk = release_artifact()
        if os.environ.get("IPTV_QA_APK"):
            apk = Path(os.environ["IPTV_QA_APK"])
    return verify_installed(device, apk, entry)


def verify_release(serial, device_type, orientation=None):
    device = Device(serial, device_type)
    entry, checksum = verify_release_apk(device)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    result = {"serial": serial, "model": device.shell("getprop", "ro.product.model").strip(),
              "android": device.shell("getprop", "ro.build.version.release").strip(),
              "versionName": entry["versionName"], "versionCode": entry["versionCode"],
              "sha256": checksum, "passed": False, "displayBounds": bounds(device.tree().find("node"))}
    try:
        _, _, width, height = result["displayBounds"]
        if orientation is not None:
            assert (height > width) == (orientation == "portrait"), "Rotate the device using its normal UI first"
        verify_forms(device, orientation or device_type)
        result["passed"] = True
    finally:
        (OUTPUT / f"{orientation or device_type}-result.json").write_text(
            json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{device_type}: forms and controls verified", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--device", choices=("phone", "tv"), required=True)
    parser.add_argument("--orientation", choices=("portrait", "landscape"))
    args = parser.parse_args()
    verify_release(args.serial, args.device, args.orientation)
