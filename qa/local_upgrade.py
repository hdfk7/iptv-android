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

from release_ui import Device, PACKAGE, QA_OUTPUT, bounds, find, release_artifact, verify_installed
from upgrade_seed import (
    HISTORY_CHANNEL, SEED_CHANNEL, SEED_NAME, close_panels, control, open_app, open_channels, seed,
    source_action, source_manager,
)


OUTPUT = QA_OUTPUT / "local-upgrade"


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


def channel_names(root):
    labels = [node.get("text") for node in root.iter("node") if node.get("text")]
    return [labels[index + 1] for index, label in enumerate(labels[:-1])
            if len(label) == 3 and label.isdecimal()]


def snapshot(device, stage, url):
    open_app(device)
    source_manager(device)
    root = device.tree()
    favorite = find(root, "收藏")
    assert favorite is not None, "Favorites row is missing"
    x, y, _, _ = bounds(favorite)
    counts = [node.get("text") for node in root.iter("node") if node.get("text", "").isdecimal()
              and abs(bounds(node)[0] - x) < 35 and 0 < bounds(node)[1] - y < 130]
    assert counts and int(counts[0]) > 0, "Nonempty favorites are required"

    # Visit the seeded source through its normal entry, even if the launcher selected another source.
    source_action(device, SEED_NAME)
    source_manager(device)
    root = device.tree()
    urls = [node.get("text") for node in root.iter("node")
            if node.get("text", "").startswith(("http://", "https://"))]
    assert url in urls, "The test subscription address is missing"
    source_action(device, "收藏")
    open_channels(device)
    favorites = channel_names(device.tree())
    assert SEED_CHANNEL in favorites, "Seeded favorite is missing from visible rows"
    device.screenshot(device.device_type, f"upgrade-{stage}-favorites")

    source_action(device, SEED_NAME)
    open_channels(device)
    if device.device_type == "tv":
        device.key("KEYCODE_DPAD_LEFT")
        # Recent is the rightmost group in the controlled source.
        device.choose_with_remote("最近", "KEYCODE_DPAD_RIGHT", limit=4)
    else:
        device.click("最近")
    root = device.tree()
    recent = channel_names(root)
    assert HISTORY_CHANNEL in recent, "The previously watched channel is missing"
    device.screenshot(device.device_type, f"upgrade-{stage}-recent")
    close_panels(device)
    return {"visibleSubscriptionHashes": sorted(hashlib.sha256(url.encode()).hexdigest() for url in urls),
            "favoriteCount": int(counts[0]), "visibleFavorites": sorted(favorites), "visibleRecent": sorted(recent)}


def wait_for_ui(device, labels, timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            root = device.tree()
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            time.sleep(0.5)
            continue
        for label in labels:
            node = find(root, label)
            if node is None:
                node = find(root, label, "content-desc")
            if node is not None:
                return label, node
    raise AssertionError(f"Expected UI did not appear: {labels}")


def update_action(device, label, node):
    if device.device_type == "tv":
        direction = "KEYCODE_DPAD_LEFT" if label == "重新下载" else "KEYCODE_DPAD_RIGHT"
        device.choose_with_remote(label, direction, limit=6)
    else:
        device.tap(node)


def install_in_app(device, expected):
    root = device.tree()
    installer_controls = ("安装", "Install", "INSTALL", "继续安装", "已知悉风险")
    if any(control(root, text) is not None for text in installer_controls):
        raise AssertionError("Finish the visible system installer, then rerun the upgrade phase")
    if find(root, "检查应用更新") is None or control(root, "关闭设置") is not None:
        open_app(device)
        close_panels(device)
        device.more()
        device.menu("设置")
        if device.device_type == "tv":
            device.key("KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_CENTER")
        else:
            device.click("检查应用更新")
    label, node = wait_for_ui(device, ("确认下载", "去授权", "安装更新"))
    if label != "确认下载":
        # The public artifact can be replaced while its versionCode stays the same.
        redownload = find(device.tree(), "重新下载")
        if redownload is not None:
            update_action(device, "重新下载", redownload)
            label, node = wait_for_ui(device, ("去授权", "安装更新"), timeout=150)
    if label == "确认下载":
        update_action(device, label, node)
        label, node = wait_for_ui(device, ("去授权", "安装更新"), timeout=150)
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
    install_labels = ("INSTALL", "Install", "安装", "继续安装")
    label, node = wait_for_ui(device, ("已知悉风险", "开发者身份未验证", "安全验证", *install_labels))
    if label in ("已知悉风险", "开发者身份未验证", "安全验证"):
        raise AssertionError("Installer is waiting for its on-device confirmation; resume this run after handling it")
    if device.device_type == "tv":
        for _ in range(6):
            root = device.tree()
            button = find(root, label)
            if button is not None and any(n.get("focused") == "true" and find(n, label) is not None
                                          for n in root.iter("node")):
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


def write_json(path, value):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def main():
    sys.stdout.reconfigure(errors="backslashreplace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phone", required=True, help="ADB serial of the physical phone")
    parser.add_argument("--tv", required=True, help="ADB serial of the TV or TV emulator")
    parser.add_argument("--old-apk", type=Path, required=True)
    parser.add_argument("--new-apk", type=Path, help="Exact distributed APK; defaults to the local Release artifact")
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--phase", choices=("baseline", "upgrade"), required=True)
    parser.add_argument("--fixture-port", type=int, default=18764)
    args = parser.parse_args()
    if args.phone == args.tv:
        parser.error("Select two different devices")
    old_apk = args.old_apk.resolve()
    new_apk = args.new_apk.resolve() if args.new_apk else release_artifact()[1].resolve()
    package, old_entry, old_signers = apk_identity(old_apk, args.build_tools)
    new_package, new_entry, new_signers = apk_identity(new_apk, args.build_tools)
    if package != PACKAGE or new_package != PACKAGE or old_signers != new_signers:
        parser.error("Both APKs must use the IPTV package and the same formal signing certificate")
    if any(path.is_relative_to(QA_OUTPUT.resolve()) for path in (old_apk, new_apk)):
        parser.error("Keep APKs outside the temporary QA output directory")
    if old_entry["versionCode"] >= new_entry["versionCode"]:
        parser.error("The candidate must have a greater versionCode")
    devices = [Device(args.phone, "phone"), Device(args.tv, "tv")]
    old_hash = hashlib.sha256(old_apk.read_bytes()).hexdigest()
    new_hash = hashlib.sha256(new_apk.read_bytes()).hexdigest()
    identity = {"phone": args.phone, "tv": args.tv, "from": old_entry, "to": new_entry,
                "oldSha256": old_hash, "candidateSha256": new_hash, "fixturePort": args.fixture_port}
    url = f"http://127.0.0.1:{args.fixture_port}/release.m3u"
    guide = f"http://127.0.0.1:{args.fixture_port}/release-guide.xml"
    OUTPUT.mkdir(parents=True, exist_ok=True)
    checkpoint = OUTPUT / "paired-run.json"

    if args.phase == "baseline":
        if not (QA_OUTPUT / ".iptv-qa-run").is_file():
            parser.error("Prepare a marked temporary QA run directory as described in qa/README.md")
        if checkpoint.exists():
            parser.error("Use a fresh QA output directory for a new baseline")
        for device in devices:
            verify_installed(device, old_apk, old_entry)
        records = {}
        for device in devices:
            seed(device, url, guide)
            records[device.device_type] = {
                "model": device.shell("getprop", "ro.product.model").strip(),
                "android": device.shell("getprop", "ro.build.version.release").strip(),
                "before": snapshot(device, "before", url),
            }
        write_json(checkpoint, {"identity": identity, "devices": records})
        print("Both old-version baselines are ready", flush=True)
        return

    if not checkpoint.is_file():
        parser.error("Collect both old-version baselines in this run before upgrading")
    state = json.loads(checkpoint.read_text(encoding="utf-8"))
    if state["identity"] != identity or set(state["devices"]) != {"phone", "tv"}:
        parser.error("Device pair, APKs or fixture do not match the recorded baseline")
    for kind, record in state["devices"].items():
        before = record.get("before", {})
        if (not before.get("visibleSubscriptionHashes") or not before.get("favoriteCount")
                or SEED_CHANNEL not in before.get("visibleFavorites", [])
                or HISTORY_CHANNEL not in before.get("visibleRecent", [])):
            parser.error(f"Incomplete old-version baseline: {kind}")
    # Check both devices before pressing the first installer button. A retry can
    # verify an already-installed candidate when the prior attempt was interrupted.
    pending = []
    for device in devices:
        record = state["devices"][device.device_type]
        if record.get("passed"):
            verify_installed(device, new_apk, new_entry)
            continue
        try:
            verify_installed(device, old_apk, old_entry)
        except AssertionError:
            if not record.get("upgradeStarted"):
                raise
            verify_installed(device, new_apk, new_entry)
            pending.append((device, False))
        else:
            pending.append((device, True))

    for device, install in pending:
        record = state["devices"][device.device_type]
        if install:
            record["upgradeStarted"] = True
            write_json(checkpoint, state)
            install_in_app(device, new_entry)
        verify_installed(device, new_apk, new_entry)
        record["after"] = snapshot(device, "after", url)
        record["passed"] = record["before"] == record["after"]
        write_json(checkpoint, state)
        assert record["passed"], f"{device.device_type}: visible data differs after the upgrade"
    print("Both in-app upgrades and data checks passed", flush=True)


if __name__ == "__main__":
    main()
