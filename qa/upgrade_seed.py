"""Prepare controlled subscription, favorites and history through the old App UI."""

import sys

# Set this before importing QA modules, including when run without -B.
sys.dont_write_bytecode = True

import io
import os
from pathlib import Path
import time

from PIL import Image

from release_ui import Device, PACKAGE, bounds, find, focus_color_present, tv_highlight, verify_release_apk


SEED_NAME = "IPTV-QA-Upgrade-" + Path(os.environ.get("IPTV_QA_OUTPUT", "local")).name.rsplit("-", 1)[-1][:8]
SEED_CHANNEL = "QA Release"
HISTORY_CHANNEL = "QA History"


def control(root, label):
    item = find(root, label)
    return item if item is not None else find(root, label, "content-desc")


def channel_panel_open(root):
    if any(control(root, label) is not None for label in ("全部", "暂无节目", "暂无收藏")):
        return True
    return any(len(node.get("text", "")) == 3 and node.get("text", "").isdecimal()
               for node in root.iter("node"))


def type_in_field(device, value):
    field = next((node for node in device.tree().iter("node")
                  if node.get("focused") == "true" and node.get("class") == "android.widget.EditText"), None)
    assert field is not None, "An editable field must have focus before typing"
    previous = field.get("text", "")
    if previous:
        device.key("KEYCODE_MOVE_END")
        device.key(*(["KEYCODE_DEL"] * len(previous)))
    device.input("text", "'" + value.replace("'", "'\\''") + "'")
    device.wait_for(value)


def close_keyboard(device):
    device.key("KEYCODE_BACK")
    if device.device_type == "tv":
        time.sleep(1.5)


def open_app(device):
    focus = device.shell("dumpsys", "window").partition("mCurrentFocus=")[2].partition("\n")[0]
    if PACKAGE not in focus:
        device.start()


def add_device_arguments(parser):
    parser.add_argument("--serial", required=True)
    parser.add_argument("--device", choices=("phone", "tv"), required=True)


def prepare_release_device(serial, device_type):
    device = Device(serial, device_type)
    entry, checksum = verify_release_apk(device)
    open_app(device)
    return device, entry, checksum


def close_panels(device):
    labels = ("刷新频道", "添加源", "删除源", "关闭设置", "检查应用更新", "IP 地址",
              "播放列表地址", "恢复默认值", "网络连接超时", "网络读取超时",
              "播放超时后自动换线", "更新检查地址")

    def panel_open(root):
        return channel_panel_open(root) or any(control(root, label) is not None for label in labels)

    for _ in range(6):
        root = device.tree()
        if not panel_open(root):
            return
        device.key("KEYCODE_BACK")
    if panel_open(device.tree()):
        raise AssertionError("App panel did not close")


def source_manager(device):
    close_panels(device)
    device.more()
    if device.device_type == "tv":
        device.choose_with_remote("源管理", "KEYCODE_DPAD_UP")
    else:
        device.menu("源管理")
    device.wait_for("添加源", "content-desc")


def action_row(root, title, action):
    row = find(root, title)
    if row is None:
        return None
    _, row_top, _, row_bottom = bounds(row)
    middle = (row_top + row_bottom) / 2
    matches = []
    for node in root.iter("node"):
        if node.get("content-desc") == action:
            _, top, _, bottom = bounds(node)
            if top <= middle < bottom:
                matches.append(node)
    return matches[0] if len(matches) == 1 else None


def source_action(device, title):
    television = device.device_type == "tv"
    source_manager(device)
    if television:
        # Start from the header to visit every row in order, including scrolled rows.
        for _ in range(30):
            if "关闭" in tv_highlight(device):
                break
            device.key("KEYCODE_DPAD_UP")
        else:
            raise AssertionError("Source manager header is not reachable")
        for _ in range(30):
            device.key("KEYCODE_DPAD_DOWN")
            root = device.tree()
            if "添加源" in tv_highlight(device, root):
                break
            button = action_row(root, title, "启用")
            if button is None:
                if action_row(root, title, "正在使用") is not None:
                    close_panels(device)
                    return
                continue
            data = device.adb("exec-out", "screencap", "-p")
            with Image.open(io.BytesIO(data)) as screenshot:
                if not focus_color_present(screenshot.convert("RGB"), button):
                    continue
            device.key("KEYCODE_DPAD_CENTER")
            return
    else:
        for _ in range(12):
            root = device.tree()
            button = action_row(root, title, "启用")
            if button is not None:
                device.tap(button)
                return
            if action_row(root, title, "正在使用") is not None:
                close_panels(device)
                return
            panel = next((item for item in root.iter("node") if item.get("scrollable") == "true"), None)
            if panel is None:
                break
            left, top, right, bottom = bounds(panel)
            x = str((left + right) // 2)
            device.input("swipe", x, str(bottom - 20), x, str(top + 20), "350")
    raise AssertionError(f"Source is not reachable: {title}")


def source_row_action(device, title, action):
    """Find a subscription action, including rows outside the initial viewport."""
    if action not in ("编辑源", "删除源"):
        raise ValueError(f"Unsupported source row action: {action}")
    source_manager(device)
    if device.device_type == "tv":
        for _ in range(30):
            if "关闭" in tv_highlight(device):
                break
            device.key("KEYCODE_DPAD_UP")
        else:
            raise AssertionError("Source manager header is not reachable")
        for _ in range(30):
            labels = tv_highlight(device)
            if title in labels:
                steps = 1 if action == "编辑源" else 2
                device.key(*(["KEYCODE_DPAD_RIGHT"] * steps))
                device.choose_with_remote(action, limit=1)
                return
            if "添加源" in labels:
                break
            device.key("KEYCODE_DPAD_DOWN")
    else:
        for _ in range(12):
            root = device.tree()
            button = action_row(root, title, action)
            panel = next((node for node in root.iter("node") if node.get("scrollable") == "true"), None)
            if button is not None and panel is None:
                device.tap(button)
                return
            if panel is None:
                break
            left, top, right, bottom = bounds(panel)
            if button is not None:
                _, button_top, _, button_bottom = bounds(button)
                if top <= button_top and button_bottom <= bottom:
                    device.tap(button)
                    return
            x = str((left + right) // 2)
            start, end = (top + 20, bottom - 20) if button is not None and button_top < top else (bottom - 20, top + 20)
            device.input("swipe", x, str(start), x, str(end), "350")
    raise AssertionError(f"Source action is not reachable: {title}: {action}")


def open_channels(device):
    close_panels(device)
    if device.device_type == "tv":
        device.key("KEYCODE_DPAD_LEFT")
        if not channel_panel_open(device.tree()):
            device.key("KEYCODE_DPAD_LEFT")
    else:
        for _ in range(3):
            device.tap(device.player_control("频道"))
            if channel_panel_open(device.tree()):
                return
        raise AssertionError("Channel list did not open after reopening the playback controls")
    assert channel_panel_open(device.tree()), "Channel list did not open"


def add_source(device, name, url, guide):
    source_manager(device)
    if device.device_type == "tv":
        device.choose_with_remote("添加源")
        for direction, value in (("DOWN", name), ("RIGHT", url), ("DOWN", guide)):
            device.key("KEYCODE_DPAD_" + direction, "KEYCODE_DPAD_CENTER")
            type_in_field(device, value)
            close_keyboard(device)
        device.key("KEYCODE_DPAD_DOWN")
        device.choose_with_remote("保存", direction="KEYCODE_DPAD_RIGHT", limit=2)
    else:
        device.click("添加源", "content-desc")
        for label, value in (("源名称", name), ("播放列表地址", url), ("XMLTV 地址（选填）", guide)):
            device.click(label)
            type_in_field(device, value)
            close_keyboard(device)
        device.click("保存", "content-desc")
    device.wait_for("添加源", "content-desc")


def select_channel(device, name):
    if device.device_type == "tv":
        root = device.tree()
        current = tv_highlight(device, root)
        direction = "KEYCODE_DPAD_DOWN" if name == HISTORY_CHANNEL else "KEYCODE_DPAD_UP"
        assert any(label.isdecimal() for label in current), current
        device.choose_with_remote(name, direction, limit=4)
    else:
        for _ in range(5):
            root = device.tree()
            rows = [item for item in root.iter("node") if item.get("clickable") == "true"
                    and find(item, name) is not None]
            if rows:
                device.tap(rows[-1])
                return
            catalog = next((node for node in root.iter("node") if node.get("scrollable") == "true"
                            and channel_panel_open(node)), None)
            if catalog is None:
                break
            left, top, right, bottom = bounds(catalog)
            x = str((left + right) // 2)
            upper = top + (bottom - top) // 4
            lower = bottom - (bottom - top) // 4
            start, end = (lower, upper) if name == HISTORY_CHANNEL else (upper, lower)
            device.input("swipe", x, str(start), x, str(end), "350")
        raise AssertionError(f"Controlled test channel is missing: {name}")


def seed(device, url, guide):
    open_app(device)
    add_source(device, SEED_NAME, url, guide)
    source_action(device, SEED_NAME)
    for _ in range(6):
        open_channels(device)
        if find(device.tree(), SEED_CHANNEL) is not None:
            break
        close_panels(device)
        time.sleep(1)
    else:
        raise AssertionError("Controlled test channel did not load")
    select_channel(device, HISTORY_CHANNEL)
    time.sleep(3)
    open_channels(device)
    select_channel(device, SEED_CHANNEL)
    time.sleep(3)
    if device.device_type == "tv":
        device.key("KEYCODE_INFO")
        device.choose_with_remote("收藏节目", "KEYCODE_DPAD_RIGHT", limit=4)
        device.key("KEYCODE_INFO")
    else:
        device.tap(device.player_control("收藏节目"))
    device.wait_for_player_control("取消收藏")
    close_panels(device)
