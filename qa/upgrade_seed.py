"""Add a temporary subscription and a favorite on the installed upgrade baseline."""

import argparse

from release_ui import Device, PACKAGE, bounds, find, tv_highlight


def type_in_field(device, value):
    # Quote for the Android shell and use the focused field's normal input path.
    device.input("text", "'" + value.replace("'", "'\\''") + "'")
    root = device.tree()
    assert find(root, value) is not None, "The keyboard did not enter the expected field value"


def find_source(device, name):
    root = device.tree()
    if find(root, name) is None and device.device_type == "tv":
        for _ in range(8):
            if "添加源" in tv_highlight(device, root):
                break
            device.key("KEYCODE_DPAD_DOWN")
            root = device.tree()
            if find(root, name) is not None:
                break
    if find(root, name) is None and device.device_type == "phone":
        for _ in range(5):
            panel = next((node for node in root.iter("node") if node.get("scrollable") == "true"), None)
            if panel is None:
                break
            left, top, right, bottom = bounds(panel)
            x = str((left + right) // 2)
            device.input("swipe", x, str(bottom - 20), x, str(top + 20), "350")
            root = device.tree()
            if find(root, name) is not None:
                break
    return find(root, name)


def seed(device, url, guide, name):
    tv = device.device_type == "tv"
    open_app(device)
    device.more()
    device.menu("源管理")
    existing = find_source(device, name)
    if existing is None and tv:
        device.choose_with_remote("添加源")
        for value, direction in ((name, "KEYCODE_DPAD_DOWN"), (url, "KEYCODE_DPAD_RIGHT"),
                                 (guide, "KEYCODE_DPAD_DOWN")):
            device.key(direction, "KEYCODE_DPAD_CENTER")
            type_in_field(device, value)
            device.key("KEYCODE_BACK")
        device.key("KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_CENTER")
    elif existing is None:
        device.click("添加源", "content-desc")
        for label, value in (("源名称", name), ("播放列表地址", url), ("XMLTV 地址（选填）", guide)):
            device.click(label)
            type_in_field(device, value)
            device.key("KEYCODE_BACK")
        device.click("保存", "content-desc")
    assert find_source(device, name) is not None, "Test subscription was not saved"

    device.key("KEYCODE_BACK", "KEYCODE_BACK")
    if tv:
        device.key("KEYCODE_INFO", "KEYCODE_DPAD_RIGHT", "KEYCODE_DPAD_RIGHT")
        highlight = tv_highlight(device)
        assert highlight in (["收藏节目"], ["取消收藏"]), highlight
        if highlight == ["收藏节目"]:
            device.key("KEYCODE_DPAD_CENTER")
            device.key("KEYCODE_INFO")
            assert "取消收藏" in tv_highlight(device)
            return True
    else:
        device.key("KEYCODE_MEDIA_PAUSE")
        root = device.tree()
        if find(root, "收藏节目", "content-desc") is not None:
            device.click("收藏节目", "content-desc")
            device.key("KEYCODE_MEDIA_PAUSE")
            assert find(device.tree(), "取消收藏", "content-desc") is not None
            return True
        assert find(root, "取消收藏", "content-desc") is not None
    return False


def open_app(device):
    focus = device.shell("dumpsys", "window").partition("mCurrentFocus=")[2].partition("\n")[0]
    if PACKAGE not in focus:
        device.start()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", choices=("phone", "tv"), required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--url", required=True)
    parser.add_argument("--guide", required=True)
    parser.add_argument("--name", default="IPTV-QA-Upgrade")
    args = parser.parse_args()
    print(f"Temporary favorite added: {seed(Device(args.serial, args.device), args.url, args.guide, args.name)}")
