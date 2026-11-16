"""Verify a system installer rejection with a staged, genuinely rejected update."""

import argparse
import json
import time

from local_upgrade import confirm_system_control, update_action, wait_for_ui
from release_ui import Device, PACKAGE, QA_OUTPUT, verify_release_apk
from upgrade_seed import control

REJECTION = (
    "安装包已损坏", "应用未安装", "App not installed", "系统拒绝安装", "系统限制了应用安装",
    "安装包版本低于当前版本", "系统无法读取安装包", "安装失败",
)
INSTALL = ("安装", "Install", "INSTALL", "继续安装", "已知悉风险")
ACKNOWLEDGE = ("完成", "Done", "确定", "OK", "关闭")


def verify_rejection(device, timeout=120):
    label, node = wait_for_ui(device, ("安装更新", *INSTALL))
    if label == "安装更新":
        update_action(device, label, node)
    system_confirmed = False
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        root = device.tree()
        for label in REJECTION:
            message = control(root, label)
            if message is not None and (system_confirmed or message.get("package") not in (None, PACKAGE)):
                device.screenshot(device.device_type, "system-install-rejection")
                for action in ACKNOWLEDGE:
                    button = control(root, action)
                    if button is not None:
                        confirm_system_control(device, action, button)
                        break
                return label
        for label in INSTALL:
            button = control(root, label)
            if (button is not None and button.get("package") not in (None, PACKAGE)
                    and button.get("enabled", "true") == "true"
                    and button.get("checked") != "true"):
                confirm_system_control(device, label, button)
                system_confirmed = True
                break
        time.sleep(0.5)
    raise AssertionError("No explicit system rejection was observed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--device", choices=("phone", "tv"), required=True)
    args = parser.parse_args()
    device = Device(args.serial, args.device)
    entry, checksum = verify_release_apk(device)
    rejection = verify_rejection(device)
    _, after = verify_release_apk(device)
    assert after == checksum, "Installed APK changed after rejection"
    report = {"device": args.device, "version": entry["versionName"], "version_code": entry["versionCode"],
              "sha256": checksum, "rejection": rejection}
    QA_OUTPUT.mkdir(parents=True, exist_ok=True)
    (QA_OUTPUT / f"update-rejection-{args.device}.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
