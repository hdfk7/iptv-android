"""Finish a full run after IPTV was reset on both devices using the system UI."""

import argparse
import hashlib
import json
import shutil
from pathlib import Path
from tempfile import gettempdir

from release_ui import Device, QA_OUTPUT, bounds, find, release_artifact, verify_installed
from upgrade_seed import action_row, close_panels, control, open_app, source_manager


def verify_reset(device, apk, entry):
    verify_installed(device, apk, entry)
    open_app(device)
    source_manager(device)
    root = device.tree()
    assert all(find(root, name) is None for name in ("IPTV-QA-Upgrade", "IPTV-QA-HLS", "IPTV-QA-Proxy"))
    assert action_row(root, "国内精选", "正在使用") is not None, "Default subscription is not active"
    favorite = find(root, "收藏")
    zero = find(root, "0")
    assert favorite is not None and zero is not None, "Favorites are not empty"
    left, top, _, _ = bounds(favorite)
    assert abs(bounds(zero)[0] - left) < 35 and 0 < bounds(zero)[1] - top < 130
    assert sum(node.get("text", "").startswith(("http://", "https://"))
               for node in root.iter("node")) == 1, "Unexpected subscriptions remain"
    close_panels(device)
    device.more()
    device.menu("设置")
    if device.device_type == "tv":
        device.choose_with_remote("HTTP 代理", limit=1)
    else:
        device.click("HTTP 代理")
    assert control(device.tree(), "已关闭") is not None, "The App proxy is still enabled"
    close_panels(device)
    device.cleanup_hierarchy()


def remove_run_files(run):
    base = Path(gettempdir()).resolve()
    root = run.resolve()
    if root.parent != base or not root.name.startswith("iptv-qa-"):
        raise ValueError("QA output must be a dedicated iptv-qa-* directory in the host temp directory")
    if (root / ".iptv-qa-run").is_file() and not (root / ".iptv-qa-run").is_symlink():
        shutil.rmtree(root)
    else:
        raise ValueError("QA run marker is missing")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phone", required=True)
    parser.add_argument("--tv", required=True)
    parser.add_argument("--new-apk", type=Path)
    args = parser.parse_args()
    if args.phone == args.tv:
        parser.error("Select two different devices")
    run = QA_OUTPUT.resolve()
    checkpoint = run / "local-upgrade" / "paired-run.json"
    state = json.loads(checkpoint.read_text(encoding="utf-8"))
    if not all(state["devices"][kind].get("passed") for kind in ("phone", "tv")):
        parser.error("Complete both device upgrades before cleanup")
    identity = state["identity"]
    if (args.phone, args.tv) != (identity["phone"], identity["tv"]):
        parser.error("This device pair does not match the run")
    apk = (args.new_apk or release_artifact()[1]).resolve()
    if apk.is_relative_to(run):
        parser.error("Keep the current APK outside the temporary QA directory")
    if hashlib.sha256(apk.read_bytes()).hexdigest() != identity["candidateSha256"]:
        parser.error("The APK does not match the candidate recorded for this run")
    for serial, kind in ((args.phone, "phone"), (args.tv, "tv")):
        verify_reset(Device(serial, kind), apk, identity["to"])
        print(f"{kind}: latest package retained and fresh App state verified", flush=True)
    remove_run_files(run)
    print("Host run files removed", flush=True)


if __name__ == "__main__":
    main()
