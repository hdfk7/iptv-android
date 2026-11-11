"""Install the signed Release APK and check form layout on an attached Android device."""

import argparse
import hashlib
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

    def screenshot(self, mode, name):
        OUTPUT.mkdir(parents=True, exist_ok=True)
        (OUTPUT / f"{mode}-{name}.png").write_bytes(self.adb("exec-out", "screencap", "-p"))


def find(root, label, attr="text"):
    return next((node for node in root.iter("node") if node.get(attr) == label), None)


def bounds(node):
    return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))


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
    device.click("添加源", "content-desc")
    check_form(device.tree(), ("源名称", "播放列表地址", "XMLTV 地址（选填）"))
    device.screenshot(mode, "add-source")
    device.click("取消", "content-desc")
    device.key("KEYCODE_BACK")

    device.start()
    device.more()
    device.menu("设置")
    if device.device_type == "tv":
        root = device.tree()
        if not any(node.get("focused") == "true" and find(node, "HTTP 代理") is not None
                   for node in root.iter("node")):
            raise AssertionError("TV settings did not focus HTTP 代理")
        device.key("KEYCODE_DPAD_CENTER")
    else:
        device.click("HTTP 代理")
    check_form(device.tree(), ("IP 地址", "端口", "用户名（可选）", "密码"))
    device.screenshot(mode, "proxy")
    device.key("KEYCODE_BACK")
    if device.device_type == "tv":
        root = device.tree()
        if not any(node.get("focused") == "true" and find(node, "HTTP 代理") is not None
                   for node in root.iter("node")):
            raise AssertionError("TV proxy did not restore the settings focus")
    device.key("KEYCODE_BACK")


def verify_release(serial, device_type):
    device = Device(serial, device_type)
    entry = json.loads((APK_DIR / "output-metadata.json").read_text(encoding="utf-8"))["elements"][0]
    apk = APK_DIR / entry["outputFile"]
    if "Success" not in device.adb("install", "-r", str(apk)).decode("utf-8"):
        raise AssertionError("Release APK did not install")
    installed = device.shell("pm", "path", PACKAGE).splitlines()[0].removeprefix("package:")
    local_hash = hashlib.sha256(apk.read_bytes()).hexdigest()
    if device.shell("sha256sum", installed).split()[0] != local_hash:
        raise AssertionError("Installed APK does not match the Release build")
    print(f"{serial} {entry['versionName']} ({entry['versionCode']}) {local_hash}", flush=True)

    rotation = device.shell("wm", "user-rotation").split() if device_type == "phone" else None
    try:
        if rotation is None:
            verify_forms(device, "tv")
        else:
            for mode, value in (("portrait", "0"), ("landscape", "1")):
                device.shell("wm", "user-rotation", "lock", value)
                verify_forms(device, mode)
    finally:
        if rotation is not None:
            device.shell("wm", "user-rotation", *rotation)
        device.shell("rm", "-f", REMOTE_XML)
    print(f"{device_type}: forms and controls verified", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--device", choices=("phone", "tv"), required=True)
    args = parser.parse_args()
    verify_release(args.serial, args.device)
