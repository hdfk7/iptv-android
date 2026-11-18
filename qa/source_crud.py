"""Exercise source CRUD through UI with a unique temporary subscription."""

import argparse
import json
import time

from release_ui import Device, QA_OUTPUT, tv_highlight, verify_release_apk
from upgrade_seed import (add_source, close_keyboard, close_panels, source_action,
                          source_row_action, type_in_field, open_app)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--device", choices=("phone", "tv"), required=True)
    parser.add_argument("--origin", default="http://qa-iptv.test",
                        help="Use the QA proxy host while the App proxy is enabled")
    args = parser.parse_args()
    device = Device(args.serial, args.device)
    verify_release_apk(device)
    open_app(device)
    television = args.device == "tv"
    name = f"IPTV-QA-CRUD-{time.time_ns()}"
    renamed = name + "-Edited"
    results = []

    def mark(label):
        device.screenshot(args.device, "crud-" + label)
        results.append(label)
        print(label + " passed", flush=True)

    try:
        suffix = "?crud=" + name
        add_source(device, name, args.origin + "/release.m3u" + suffix,
                   args.origin + "/release-guide.xml" + suffix)
        # A saved source can be below the initial viewport. Navigate to it before asserting.
        source_row_action(device, name, "编辑源")
        device.wait_for("播放列表地址")
        mark("add-source")
        if television:
            device.key("KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_CENTER")
        else:
            device.click("源名称")
        type_in_field(device, renamed)
        close_keyboard(device)
        if television:
            device.key("KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_DOWN")
            device.choose_with_remote("保存", direction="KEYCODE_DPAD_RIGHT", limit=2)
        else:
            device.click("保存", "content-desc")
        source_row_action(device, renamed, "删除源")
        device.wait_for("删除「" + renamed + "」？")
        mark("edit-source")
        if television:
            assert "取消" in tv_highlight(device)
            device.key("KEYCODE_DPAD_CENTER")
        else:
            device.click("取消", "content-desc")
        source_row_action(device, renamed, "删除源")
        device.wait_for("删除「" + renamed + "」？")
        mark("cancel-delete")
        if television:
            device.choose_with_remote("删除源", direction="KEYCODE_DPAD_RIGHT", limit=2)
        else:
            device.click("删除源", "content-desc")
        device.wait_for("添加源", "content-desc")
        try:
            source_action(device, renamed)
        except AssertionError as failure:
            if str(failure) != f"Source is not reachable: {renamed}":
                raise
        else:
            raise AssertionError("Deleted source remains reachable")
        mark("confirm-delete")
        close_panels(device)
    finally:
        output = QA_OUTPUT / "functional"
        output.mkdir(parents=True, exist_ok=True)
        (output / (args.device + "-crud.json")).write_text(
            json.dumps({"temporarySource": name, "renamedSource": renamed, "passedSteps": results},
                       ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
