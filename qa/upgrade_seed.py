"""Add a temporary subscription and a favorite on the installed upgrade baseline."""

import argparse

from release_ui import Device, find, tv_highlight


def seed(device, url, guide, name):
    tv = device.device_type == "tv"
    device.start()
    device.more()
    device.menu("源管理")
    if tv:
        device.choose_with_remote("添加源")
        for value, direction in ((name, "KEYCODE_DPAD_DOWN"), (url, "KEYCODE_DPAD_RIGHT"),
                                 (guide, "KEYCODE_DPAD_RIGHT")):
            device.key(direction, "KEYCODE_DPAD_CENTER")
            device.input("text", value)
            device.key("KEYCODE_BACK")
        device.choose_with_remote("保存")
    else:
        device.click("添加源", "content-desc")
        for label, value in (("源名称", name), ("播放列表地址", url), ("XMLTV 地址（选填）", guide)):
            device.click(label)
            device.input("text", value)
            device.key("KEYCODE_BACK")
        device.click("保存", "content-desc")
    assert find(device.tree(), name) is not None, "Test subscription was not saved"

    device.start()
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
            assert find(device.tree(), "取消收藏", "content-desc") is not None
            return True
        assert find(root, "取消收藏", "content-desc") is not None
    return False


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", choices=("phone", "tv"), required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--url", required=True)
    parser.add_argument("--guide", required=True)
    parser.add_argument("--name", default="IPTV-QA-Upgrade")
    args = parser.parse_args()
    print(f"Temporary favorite added: {seed(Device(args.serial, args.device), args.url, args.guide, args.name)}")
