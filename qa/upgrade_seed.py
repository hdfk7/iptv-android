"""Prepare controlled subscription, favorites and history through the old App UI."""

import io
import os
from pathlib import Path
import time

from PIL import Image

from release_ui import PACKAGE, bounds, find, focus_color_present, tv_highlight


SEED_NAME = "IPTV-QA-Upgrade-" + Path(os.environ.get("IPTV_QA_OUTPUT", "local")).name.rsplit("-", 1)[-1][:8]
SEED_CHANNEL = "QA Release"
HISTORY_CHANNEL = "QA History"


def control(root, label):
    item = find(root, label)
    return item if item is not None else find(root, label, "content-desc")


def type_in_field(device, value):
    device.input("text", "'" + value.replace("'", "'\\''") + "'")
    assert find(device.tree(), value) is not None, "Focused field did not receive the expected value"


def close_keyboard(device):
    device.key("KEYCODE_BACK")
    if device.device_type == "tv":
        time.sleep(1.5)


def open_app(device):
    focus = device.shell("dumpsys", "window").partition("mCurrentFocus=")[2].partition("\n")[0]
    if PACKAGE not in focus:
        device.start()


def close_panels(device):
    for _ in range(6):
        root = device.tree()
        labels = ("刷新频道", "添加源", "关闭设置", "检查应用更新", "IP 地址", "全部")
        if not any(control(root, label) is not None for label in labels):
            return
        device.key("KEYCODE_BACK")
    raise AssertionError("App panel did not close")


def source_manager(device):
    close_panels(device)
    device.more()
    if device.device_type == "tv":
        device.choose_with_remote("源管理", "KEYCODE_DPAD_UP")
    else:
        device.menu("源管理")
    assert control(device.tree(), "添加源") is not None, "Source manager did not open"


def action_row(root, title, action):
    row = find(root, title)
    if row is None:
        return None
    row_top = bounds(row)[1]
    buttons = [node for node in root.iter("node") if node.get("content-desc") == action]
    matches = [node for node in buttons if abs(bounds(node)[1] - row_top) < 95]
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


def open_channels(device):
    close_panels(device)
    if device.device_type == "tv":
        device.key("KEYCODE_DPAD_LEFT")
        if control(device.tree(), "全部") is None:
            device.key("KEYCODE_DPAD_LEFT")
    else:
        device.tap(device.player_control("频道"))
    assert control(device.tree(), "全部") is not None, "Channel list did not open"


def add_source(device, name, url, guide):
    source_manager(device)
    if device.device_type == "tv":
        device.choose_with_remote("添加源")
        for direction, value in (("DOWN", name), ("RIGHT", url), ("DOWN", guide)):
            device.key("KEYCODE_DPAD_" + direction, "KEYCODE_DPAD_CENTER")
            type_in_field(device, value)
            close_keyboard(device)
        device.key("KEYCODE_DPAD_DOWN")
        device.choose_with_remote("保存", limit=1)
    else:
        device.click("添加源", "content-desc")
        for label, value in (("源名称", name), ("播放列表地址", url), ("XMLTV 地址（选填）", guide)):
            device.click(label)
            type_in_field(device, value)
            close_keyboard(device)
        device.click("保存", "content-desc")
    assert control(device.tree(), "添加源") is not None, "Source was not saved"


def select_channel(device, name):
    if device.device_type == "tv":
        root = device.tree()
        current = tv_highlight(device, root)
        direction = "KEYCODE_DPAD_DOWN" if name == HISTORY_CHANNEL else "KEYCODE_DPAD_UP"
        assert any(label.isdecimal() for label in current), current
        device.choose_with_remote(name, direction, limit=4)
    else:
        root = device.tree()
        rows = [item for item in root.iter("node") if item.get("clickable") == "true"
                and find(item, name) is not None]
        assert rows, f"Controlled test channel is missing: {name}"
        device.tap(rows[-1])


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
    assert control(device.tree(), "取消收藏") is not None, "Test channel was not added to favorites"
    close_panels(device)
