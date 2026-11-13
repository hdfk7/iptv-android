"""Verify a data-preserving local upgrade on an Android phone or TV."""

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

from release_ui import Device, PACKAGE, QA_OUTPUT, bounds, find, verify_installed
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


def confirm_system_control(device, label, node):
    if device.device_type == "phone":
        device.tap(node)
        return
    for direction in ("RIGHT", "LEFT", "DOWN", "UP"):
        for _ in range(6):
            root = device.tree()
            if any(item.get("focused") == "true" and control(item, label) is not None
                   for item in root.iter("node")):
                device.key("KEYCODE_DPAD_CENTER")
                return
            device.key("KEYCODE_DPAD_" + direction)
    raise AssertionError(f"System control is not reachable: {label}")


def finish_installation(device, expected):
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        installed = device.shell("dumpsys", "package", PACKAGE)
        code = re.search(r"\bversionCode=(\d+)\s", installed)
        name = re.search(r"\bversionName=([^\r\n]+)", installed)
        if code and name and int(code[1]) == expected["versionCode"] and name[1].strip() == expected["versionName"]:
            return
        root = device.tree()
        for label in ("安装包已损坏", "应用未安装", "App not installed", "输入密码", "请输入锁屏密码", "验证指纹"):
            if control(root, label) is not None:
                raise AssertionError(f"System installer stopped at {label}; inspect this screen before resuming")
        for label in ("已知悉风险", "继续安装", "INSTALL", "Install", "安装"):
            node = control(root, label)
            if node is not None and node.get("enabled", "true") == "true":
                if node.get("checkable") == "true" and node.get("checked") == "true":
                    continue
                confirm_system_control(device, label, node)
                break
        time.sleep(0.5)
    raise AssertionError("System installer did not install the requested version; inspect the current screen")


def install_in_app(device, expected, download_timeout=900):
    root = device.tree()
    installer_controls = ("安装", "Install", "INSTALL", "继续安装", "已知悉风险")
    if any(control(root, text) is not None for text in installer_controls):
        finish_installation(device, expected)
        return
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
            label, node = wait_for_ui(device, ("去授权", "安装更新"), timeout=download_timeout)
    if label == "确认下载":
        update_action(device, label, node)
        label, node = wait_for_ui(device, ("去授权", "安装更新"), timeout=download_timeout)
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
        open_app(device)
        label, node = wait_for_ui(device, ("安装更新",))
    update_action(device, label, node)
    finish_installation(device, expected)


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
    parser.add_argument("--new-apk", type=Path, help="Exact distributed APK, required for upgrade")
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--phase", choices=("baseline", "upgrade"), required=True)
    parser.add_argument("--fixture-port", type=int, default=18764)
    parser.add_argument("--download-timeout", type=int, default=900, help="Seconds to wait for the App download; does not change App timeouts")
    args = parser.parse_args()
    if args.phone == args.tv:
        parser.error("Select two different devices")
    if args.download_timeout <= 0:
        parser.error("--download-timeout must be positive")
    old_apk = args.old_apk.resolve()
    package, old_entry, old_signers = apk_identity(old_apk, args.build_tools)
    if package != PACKAGE:
        parser.error("The old APK must use the IPTV package")
    if old_apk.is_relative_to(QA_OUTPUT.resolve()):
        parser.error("Keep APKs outside the temporary QA output directory")
    devices = [Device(args.phone, "phone"), Device(args.tv, "tv")]
    old_hash = hashlib.sha256(old_apk.read_bytes()).hexdigest()
    identity = {"phone": args.phone, "tv": args.tv, "from": old_entry,
                "oldSha256": old_hash, "fixturePort": args.fixture_port}
    url = f"http://127.0.0.1:{args.fixture_port}/release.m3u"
    guide = f"http://127.0.0.1:{args.fixture_port}/release-guide.xml"
    OUTPUT.mkdir(parents=True, exist_ok=True)
    checkpoint = OUTPUT / "paired-run.json"

    if args.phase == "baseline":
        if args.new_apk is not None:
            parser.error("Build the candidate after collecting both baselines")
        if not (QA_OUTPUT / ".iptv-qa-run").is_file():
            parser.error("Prepare a marked temporary QA run directory as described in qa/README.md")
        if checkpoint.exists():
            parser.error("Use a fresh QA output directory for a new baseline")
        for device in devices:
            verify_installed(device, old_apk, old_entry)
        def prepare(device):
            seed(device, url, guide)
            return {
                "model": device.shell("getprop", "ro.product.model").strip(),
                "android": device.shell("getprop", "ro.build.version.release").strip(),
                "before": snapshot(device, "before", url),
            }
        records = {}
        errors = []
        with ThreadPoolExecutor(max_workers=2) as pool:
            tasks = {pool.submit(prepare, device): device.device_type for device in devices}
            for task in as_completed(tasks):
                kind = tasks[task]
                try:
                    records[kind] = task.result()
                    print(f"{kind}: old-version baseline passed", flush=True)
                except Exception as error:
                    errors.append(f"{kind}: {error}")
                    print(errors[-1], flush=True)
        if errors:
            raise AssertionError("; ".join(errors))
        write_json(checkpoint, {"identity": identity, "devices": records})
        print("Both old-version baselines are ready", flush=True)
        return

    if not checkpoint.is_file():
        parser.error("Collect both old-version baselines in this run before upgrading")
    state = json.loads(checkpoint.read_text(encoding="utf-8"))
    if any(state["identity"].get(key) != value for key, value in identity.items()) or set(state["devices"]) != {"phone", "tv"}:
        parser.error("Device pair, old APK or fixture does not match the recorded baseline")
    for kind, record in state["devices"].items():
        before = record.get("before", {})
        if (not before.get("visibleSubscriptionHashes") or not before.get("favoriteCount")
                or SEED_CHANNEL not in before.get("visibleFavorites", [])
                or HISTORY_CHANNEL not in before.get("visibleRecent", [])):
            parser.error(f"Incomplete old-version baseline: {kind}")
    if args.new_apk is None:
        parser.error("--new-apk is required for upgrade")
    new_apk = args.new_apk.resolve()
    if new_apk.is_relative_to(QA_OUTPUT.resolve()):
        parser.error("Keep APKs outside the temporary QA output directory")
    new_package, new_entry, new_signers = apk_identity(new_apk, args.build_tools)
    if new_package != PACKAGE or old_signers != new_signers:
        parser.error("Both APKs must use the IPTV package and the same formal signing certificate")
    if old_entry["versionCode"] >= new_entry["versionCode"]:
        parser.error("The candidate must have a greater versionCode")
    new_hash = hashlib.sha256(new_apk.read_bytes()).hexdigest()
    candidate = {"to": new_entry, "candidateSha256": new_hash}
    if "candidateSha256" in state["identity"] and any(
        state["identity"].get(key) != value for key, value in candidate.items()
    ):
        parser.error("Resume with the candidate APK already recorded for this run")
    state["identity"].update(candidate)
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
        if install:
            state["devices"][device.device_type]["upgradeStarted"] = True
    write_json(checkpoint, state)

    def upgrade(device, install):
        if install:
            install_in_app(device, new_entry, args.download_timeout)
        verify_installed(device, new_apk, new_entry)
        return snapshot(device, "after", url)

    errors = []
    with ThreadPoolExecutor(max_workers=2) as pool:
        tasks = {pool.submit(upgrade, device, install): device.device_type for device, install in pending}
        for task in as_completed(tasks):
            kind = tasks[task]
            record = state["devices"][kind]
            try:
                record["after"] = task.result()
                record["passed"] = record["before"] == record["after"]
                assert record["passed"], "visible data differs after the upgrade"
                record.pop("error", None)
                print(f"{kind}: upgrade and data checks passed", flush=True)
            except Exception as error:
                record["error"] = str(error)
                errors.append(f"{kind}: {error}")
                print(errors[-1], flush=True)
            write_json(checkpoint, state)
    if errors:
        raise AssertionError("; ".join(errors))
    print("Both in-app upgrades and data checks passed", flush=True)


if __name__ == "__main__":
    main()
