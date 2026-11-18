"""Validate proxy state, invalid input and persistence through the installed UI."""

import sys

# Set this before importing QA modules, including when run without -B.
sys.dont_write_bytecode = True

import argparse
import json

from controlled_ui import control, open_proxy
from release_ui import QA_OUTPUT, bounds, find
from upgrade_seed import close_keyboard, close_panels, type_in_field, add_device_arguments, prepare_release_device


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    add_device_arguments(parser)
    parser.add_argument("--proxy-port", type=int, default=18766)
    args = parser.parse_args()
    device, _, checksum = prepare_release_device(args.serial, args.device)
    television = args.device == "tv"
    results = []

    def mark(label):
        device.screenshot(args.device, "proxy-" + label)
        results.append(label)
        print(label + " passed", flush=True)

    def reveal(label, upward):
        for _ in range(5):
            root = device.tree()
            if find(root, label) is not None:
                return
            panels = [node for node in root.iter("node") if node.get("scrollable") == "true"]
            assert panels and not television, f"Control not visible: {label}"
            panel = min(panels, key=lambda node: bounds(node)[3] - bounds(node)[1])
            left, top, right, bottom = bounds(panel)
            x = str((left + right) // 2)
            start, end = (bottom - 20, top + 20) if upward else (top + 20, bottom - 20)
            device.input("swipe", x, str(start), x, str(end), "350")
        raise AssertionError(f"Control not reachable after scrolling: {label}")

    def toggle(label):
        if television:
            device.choose_with_remote(label, limit=1)
        else:
            device.click(label, "content-desc")

    def edit_port(value):
        if television:
            device.key("KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_RIGHT", "KEYCODE_DPAD_CENTER")
        else:
            reveal("端口", upward=False)
            device.click("端口")
        type_in_field(device, value)
        close_keyboard(device)
        if television:
            device.key("KEYCODE_DPAD_UP")

    def assert_locked(root):
        fields = [node for node in root.iter("node") if node.get("class") == "android.widget.EditText"]
        assert fields and all(node.get("enabled") == "false" for node in fields)

    try:
        open_proxy(device)
        root = device.tree()
        assert control(root, "开启") is not None, "Prepare the fixture proxy before this validation"
        assert_locked(root)
        mark("enabled-fields-locked")
        toggle("开启")
        device.wait_for("关闭", "content-desc")
        edit_port("0")
        toggle("关闭")
        reveal("IP 地址、端口或认证信息无效", upward=True)
        assert find(device.tree(), "关闭", "content-desc") is not None
        mark("invalid-port-rejected")
        edit_port(str(args.proxy_port))
        toggle("关闭")
        device.wait_for("开启", "content-desc")
        device.key("KEYCODE_BACK")
        open_proxy(device)
        root = device.tree()
        assert control(root, "开启") is not None and control(root, str(args.proxy_port)) is not None
        assert_locked(root)
        mark("saved-reopened-and-locked")
        close_panels(device)
    finally:
        output = QA_OUTPUT / "functional"
        output.mkdir(parents=True, exist_ok=True)
        (output / (args.device + "-proxy-validation.json")).write_text(
            json.dumps({"sha256": checksum, "passedSteps": results}, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
