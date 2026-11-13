"""Install the signed Release APK and check form layout on an attached Android device."""

import argparse
import hashlib
import io
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path


PACKAGE = "cn.hdfk7.android.iptv"
APK_DIR = Path("app/build/outputs/apk/release")
OUTPUT = Path("app/build/outputs/qa/release-ui")
REMOTE_XML = "/data/local/tmp/iptv-release-ui.xml"


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
        self.shell("uiautomator", "dump", REMOTE_XML)
        return ET.fromstring(self.shell("cat", REMOTE_XML))

    def tap(self, node):
        left, top, right, bottom = bounds(node)
        self.input("tap", str((left + right) // 2), str((top + bottom) // 2))

    def click(self, label, attr="text"):
        node = find(self.tree(), label, attr)
        if node is None:
            raise AssertionError(f"Control not visible: {label}")
        self.tap(node)

    def start(self):
        self.shell("am", "force-stop", PACKAGE)
        self.shell("am", "start", "-W", "-n", f"{PACKAGE}/.app.MainActivity")
        time.sleep(2)

    def more(self):
        if self.device_type == "tv":
            self.key("KEYCODE_INFO")
            self.key("KEYCODE_MENU")
        else:
            self.key("KEYCODE_MEDIA_PAUSE")
            self.click("更多")

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


def tv_highlight(device, root=None, screenshot=None):
    from PIL import Image

    root = device.tree() if root is None else root
    screenshot = device.adb("exec-out", "screencap", "-p") if screenshot is None else screenshot
    highlighted = []
    focus_colors = {(57, 86, 115), (76, 102, 128)}
    with Image.open(io.BytesIO(screenshot)) as source:
        image = source.convert("RGB")
        for node in root.iter("node"):
            label = node.get("text") or node.get("content-desc")
            if not label or "://" in label:
                continue
            left, top, right, bottom = bounds(node)
            if right - left < 4 or bottom - top < 4:
                continue
            crop = image.crop((left, top, right, bottom))
            colors = crop.getcolors(crop.width * crop.height)
            if sum(count for count, color in colors if color in focus_colors) > crop.width * crop.height * 0.20:
                highlighted.append(label)
    return highlighted


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
    device.start()
    device.more()
    device.menu("源管理")
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
    device.key("KEYCODE_BACK")

    device.start()
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
    installed = device.shell("pm", "path", PACKAGE).splitlines()[0].removeprefix("package:")
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


def install_release(device):
    entry, apk = release_artifact()
    if "Success" not in device.adb("install", "-r", str(apk)).decode("utf-8"):
        raise AssertionError("Release APK did not install")
    return verify_installed(device, apk, entry)


def verify_release(serial, device_type):
    device = Device(serial, device_type)
    entry, checksum = install_release(device)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    result = {"serial": serial, "model": device.shell("getprop", "ro.product.model").strip(),
              "android": device.shell("getprop", "ro.build.version.release").strip(),
              "versionName": entry["versionName"], "versionCode": entry["versionCode"],
              "sha256": checksum, "passed": False}
    rotation = device.shell("wm", "user-rotation").split() if device_type == "phone" else None
    try:
        if rotation is None:
            verify_forms(device, "tv")
        else:
            for mode, value in (("portrait", "0"), ("landscape", "1")):
                device.shell("wm", "user-rotation", "lock", value)
                verify_forms(device, mode)
        result["passed"] = True
    finally:
        if rotation is not None:
            device.shell("wm", "user-rotation", *rotation)
        device.shell("rm", "-f", REMOTE_XML)
        (OUTPUT / f"{device_type}-result.json").write_text(
            json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{device_type}: forms and controls verified", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--device", choices=("phone", "tv"), required=True)
    args = parser.parse_args()
    verify_release(args.serial, args.device)
