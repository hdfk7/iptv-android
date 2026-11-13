"""Verify a data-preserving local upgrade on an Android phone or TV."""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

from release_ui import Device, PACKAGE, bounds, find, install_release, release_artifact, verify_installed
from upgrade_seed import seed


OUTPUT = Path("app/build/outputs/qa/local-upgrade")


def apk_identity(apk, tools):
    windows = os.name == "nt"
    aapt = tools / ("aapt.exe" if windows else "aapt")
    apksigner = tools / ("apksigner.bat" if windows else "apksigner")
    badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], encoding="utf-8")
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    if package is None:
        raise ValueError(f"APK package metadata missing: {apk}")
    certificates = subprocess.check_output([str(apksigner), "verify", "--print-certs", str(apk)], encoding="utf-8")
    signers = set(re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", certificates))
    if not signers:
        raise ValueError(f"APK signer missing: {apk}")
    return package[1], {"versionCode": int(package[2]), "versionName": package[3]}, signers


def snapshot(device, stage):
    device.start()
    device.more()
    device.menu("源管理")
    root = device.tree()
    favorite = find(root, "收藏")
    assert favorite is not None, "Favorites row is missing"
    x, y, _, _ = bounds(favorite)
    counts = [node.get("text") for node in root.iter("node") if node.get("text", "").isdecimal()
              and abs(bounds(node)[0] - x) < 35 and 0 < bounds(node)[1] - y < 130]
    assert counts, "Favorite count is missing"
    urls = [node.get("text") for node in root.iter("node") if node.get("text", "").startswith(("http://", "https://"))]
    assert urls, "No visible subscriptions to compare"

    device.start()
    if device.device_type == "tv":
        device.key("KEYCODE_DPAD_LEFT", "KEYCODE_DPAD_LEFT")
        device.choose_with_remote("最近", "KEYCODE_DPAD_LEFT")
    else:
        device.key("KEYCODE_MEDIA_PAUSE")
        device.click("频道")
        device.click("最近")
    root = device.tree()
    assert find(root, "最近") is not None, "Recent group is missing"
    # Read channel number/name pairs and ignore the changing programme previews.
    labels = [node.get("text") for node in root.iter("node") if node.get("text")]
    recent = [labels[index + 1] for index, label in enumerate(labels[:-1])
              if len(label) == 3 and label.isdecimal()]
    assert recent, "Play a channel on the active source before collecting the baseline"
    device.screenshot(device.device_type, f"upgrade-{stage}-recent")
    source_hashes = [hashlib.sha256(url.encode()).hexdigest() for url in urls]
    return {"visibleSubscriptionHashes": source_hashes, "favoriteCount": int(counts[0]), "visibleRecent": recent}


def wait_for_ui(device, labels, timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        root = device.tree()
        for label in labels:
            node = find(root, label) or find(root, label, "content-desc")
            if node is not None:
                return label, node
    raise AssertionError(f"Expected UI did not appear: {labels}")


def update_action(device, label, node):
    if device.device_type == "tv":
        device.choose_with_remote(label, "KEYCODE_DPAD_RIGHT", limit=6)
    else:
        device.tap(node)


def install_in_app(device, expected):
    device.start()
    device.more()
    device.menu("设置")
    if device.device_type == "tv":
        device.key("KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_CENTER")
    else:
        device.click("检查应用更新")
    label, node = wait_for_ui(device, ("确认下载",))
    update_action(device, label, node)
    label, node = wait_for_ui(device, ("去授权", "安装更新"))
    if label == "去授权":
        update_action(device, label, node)
        if device.device_type == "tv":
            wait_for_ui(device, ("IPTV",))
            for _ in range(10):
                root = device.tree()
                focused = [item for item in root.iter("node") if item.get("focused") == "true"]
                if any(find(item, "IPTV") is not None for item in focused):
                    device.key("KEYCODE_DPAD_CENTER")
                    break
                device.key("KEYCODE_DPAD_DOWN")
            else:
                raise AssertionError("Unknown-app permission did not focus IPTV")
        else:
            _, switch = wait_for_ui(device, ("允许来自此来源的应用", "允许来自此来源的安装", "Allow from this source"))
            device.tap(switch)
        device.key("KEYCODE_BACK")
        label, node = wait_for_ui(device, ("安装更新",))
    update_action(device, label, node)
    label, node = wait_for_ui(device, ("INSTALL", "Install", "安装", "继续安装"))
    if device.device_type == "tv":
        for _ in range(6):
            root = device.tree()
            button = find(root, label)
            if button is not None and button.get("focused") == "true":
                device.key("KEYCODE_DPAD_CENTER")
                break
            device.key("KEYCODE_DPAD_RIGHT")
        else:
            raise AssertionError("System Install button could not be reached")
    else:
        device.tap(node)
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        installed = device.shell("dumpsys", "package", PACKAGE)
        if re.search(rf"\bversionCode={expected['versionCode']}\s", installed):
            return
        time.sleep(0.5)
    raise AssertionError("System installer did not install the requested version")


def main():
    sys.stdout.reconfigure(errors="backslashreplace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--device", choices=("phone", "tv"), required=True)
    parser.add_argument("--old-apk", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--allow-downgrade", action="store_true",
                        help="Allow ADB to install the older APK over a newer version on this test device")
    parser.add_argument("--seed-url", help="Add a temporary test subscription on the old version before the upgrade")
    parser.add_argument("--seed-guide", help="XMLTV address for the temporary test subscription")
    parser.add_argument("--in-app", action="store_true", help="Download and install from the old app's update screen")
    args = parser.parse_args()
    if bool(args.seed_url) != bool(args.seed_guide):
        parser.error("--seed-url and --seed-guide must be supplied together")
    _, current_apk = release_artifact()
    package, old_entry, old_signers = apk_identity(args.old_apk.resolve(), args.build_tools)
    new_package, new_entry, new_signers = apk_identity(current_apk.resolve(), args.build_tools)
    if package != new_package or old_signers != new_signers or old_entry["versionCode"] >= new_entry["versionCode"]:
        parser.error("Old APK must have the same package and signing certificate and a lower versionCode")
    device = Device(args.serial, args.device)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    result = {"method": "in-app" if args.in_app else "local", "serial": args.serial, "model": device.shell("getprop", "ro.product.model").strip(),
              "android": device.shell("getprop", "ro.build.version.release").strip(), "from": old_entry, "to": new_entry}
    flags = ["-r", "-d"] if args.allow_downgrade else ["-r"]
    if b"Success" not in device.adb("install", *flags, str(args.old_apk.resolve())):
        raise AssertionError("Old APK did not install")
    def baseline():
        _, result["oldSha256"] = verify_installed(device, args.old_apk, old_entry)
        if args.seed_url:
            result["temporaryFavoriteAdded"] = seed(device, args.seed_url, args.seed_guide, "IPTV-QA-Upgrade")
        result["before"] = snapshot(device, "before")
        if args.seed_url:
            assert result["before"]["favoriteCount"] > 0, "Temporary data must include a nonempty favorites list"
    if args.in_app:
        baseline()
        install_in_app(device, new_entry)
        _, result["sha256"] = verify_installed(device, current_apk, new_entry)
    else:
        try:
            baseline()
        finally:
            _, result["sha256"] = install_release(device)
    result["after"] = snapshot(device, "after")
    result["passed"] = result["before"] == result["after"]
    result["nonEmptyFavoritesCovered"] = result["before"]["favoriteCount"] > 0
    (OUTPUT / f"{args.device}-result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    assert result["passed"], "Visible data differs after the upgrade"
    print(f"{args.device}: local upgrade passed", flush=True)


if __name__ == "__main__":
    main()
