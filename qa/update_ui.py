"""Check update UI and download cancellation without opening the installer."""

import sys

# Set this before importing QA modules, including when run without -B.
sys.dont_write_bytecode = True

import argparse
import json
import time

from release_ui import QA_OUTPUT, bounds, find
from upgrade_seed import close_panels, add_device_arguments, prepare_release_device


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    add_device_arguments(parser)
    args = parser.parse_args()
    device, _, checksum = prepare_release_device(args.serial, args.device)
    results = {"sha256": checksum, "passedSteps": [], "notExecuted": ["system installation"]}

    def open_update():
        close_panels(device)
        device.more()
        device.menu("设置")
        device.menu("检查应用更新")
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            root = device.tree()
            if find(root, "正在检查更新…") is None:
                return root
        raise AssertionError("Update check did not finish")

    def mark(label):
        device.screenshot(args.device, "update-" + label)
        results["passedSteps"].append(label)
        print(label + " passed", flush=True)

    def start_download():
        root = open_update()
        if find(root, "确认下载") is None:
            results["notExecuted"].append("download cancellation: no downloadable update offered")
            return False
        if args.device == "tv":
            device.choose_with_remote("确认下载", limit=1)
        else:
            device.click("确认下载")
        root = device.tree()
        if find(root, "取消下载") is None:
            results["notExecuted"].append("download cancellation: transfer finished or failed before inspection")
            results["downloadFinalText"] = [n.get("text") for n in root.iter("node") if n.get("text")]
            return False
        return True

    try:
        root = open_update()
        results["checkText"] = [n.get("text") for n in root.iter("node") if n.get("text")]
        mark("check")
        notes = next((node for node in root.iter("node") if node.get("scrollable") == "true"), None)
        before = [(node.get("text"), node.get("bounds")) for node in notes.iter("node") if node.get("text")] if notes is not None else []
        if notes is None:
            mark("notes-fit-without-scrolling")
        elif args.device == "tv":
            device.key("KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_DOWN")
        else:
            left, top, right, bottom = bounds(notes)
            x = str((left + right) // 2)
            device.input("swipe", x, str(bottom - 15), x, str(top + 15), "400")
        if notes is not None:
            after_root = device.tree()
            after = next((node for node in after_root.iter("node") if node.get("scrollable") == "true"), None)
            assert after is not None
            visible = [(node.get("text"), node.get("bounds")) for node in after.iter("node") if node.get("text")]
            assert visible != before, "Release notes did not move after scrolling"
            mark("notes-scroll")
        device.key("KEYCODE_BACK")
        device.wait_for("高级设置")
        mark("return-to-settings")
        if start_download():
            mark("download-progress")
            if args.device == "phone":
                device.input("tap", "5", "200")
                device.wait_for("取消下载")
                mark("outside-tap-keeps-download")
            device.key("KEYCODE_BACK")
            device.wait_for("高级设置")
            mark("back-cancels-download")
            if start_download():
                mark("download-retry")
                if args.device == "tv":
                    device.choose_with_remote("取消下载", limit=1)
                else:
                    device.click("取消下载")
                device.wait_for("高级设置")
                mark("button-cancels-download")
        close_panels(device)
    finally:
        output = QA_OUTPUT / "functional"
        output.mkdir(parents=True, exist_ok=True)
        (output / (args.device + "-update.json")).write_text(
            json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
