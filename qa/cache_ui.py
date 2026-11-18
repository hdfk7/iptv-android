"""Regress both devices' cache under one coordinated fixture outage."""

import sys

# Set this before importing QA modules, including when run without -B.
sys.dont_write_bytecode = True

import argparse
import json
import time
import traceback
from concurrent.futures import ThreadPoolExecutor
from urllib.request import urlopen

from controlled_ui import guide_address, programme_rows
from release_ui import Device, QA_OUTPUT, bounds, find, verify_release_apk
from upgrade_seed import (close_keyboard, close_panels, open_channels, select_channel,
                          source_row_action, type_in_field, open_app)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phone", required=True)
    parser.add_argument("--tv", required=True)
    parser.add_argument("--origin-port", type=int, default=18764)
    parser.add_argument("--resume-isolation", action="store_true", help="Resume only after this APK passed the outage and recovery phases")
    args = parser.parse_args()
    origin = f"http://127.0.0.1:{args.origin_port}"
    devices = [Device(args.phone, "phone"), Device(args.tv, "tv")]
    states = {}
    for device in devices:
        _, checksum = verify_release_apk(device)
        open_app(device)
        checkpoint = QA_OUTPUT / "controlled" / (device.device_type + "-proxy-source.json")
        prepared = json.loads(checkpoint.read_text(encoding="utf-8"))
        assert prepared["serial"] == device.serial and prepared["originPort"] == args.origin_port
        states[device.device_type] = {"sha256": checksum, "source": prepared, "passedSteps": []}
        if args.resume_isolation:
            previous = QA_OUTPUT / "functional" / (device.device_type + "-cache.json")
            state = json.loads(previous.read_text(encoding="utf-8"))
            assert state["sha256"] == checksum and state["source"] == prepared
            assert all(step in state["passedSteps"] for step in ("baseline", "503-playlist-retained", "503-guide-retained", "recovered"))
            archive = previous.with_name(device.device_type + "-cache-before-isolation-fix.json")
            if not archive.exists():
                archive.write_bytes(previous.read_bytes())
            states[device.device_type] = state

    def mark(device, label):
        device.screenshot(device.device_type, "cache-" + label)
        states[device.device_type]["passedSteps"].append(label)
        print(device.device_type + ": " + label + " passed", flush=True)

    def parallel(action):
        errors = []
        with ThreadPoolExecutor(max_workers=2) as pool:
            jobs = [(device, pool.submit(action, device)) for device in devices]
            for device, job in jobs:
                try:
                    job.result()
                except Exception as error:
                    states[device.device_type]["failure"] = repr(error)
                    states[device.device_type]["failureTraceback"] = traceback.format_exc()
                    device.screenshot(device.device_type, "cache-failure")
                    errors.append(f"{device.device_type} {action.__name__}: {error!r}")
        assert not errors, "; ".join(errors)

    def edit_source(device, address=None, guide=None):
        source_row_action(device, states[device.device_type]["source"]["name"], "编辑源")
        television = device.device_type == "tv"
        if address is not None:
            if television:
                device.key("KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_RIGHT", "KEYCODE_DPAD_CENTER")
            else:
                device.click("播放列表地址")
            type_in_field(device, address)
            close_keyboard(device)
        if guide is not None:
            if television:
                if address is None:
                    device.key("KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_CENTER")
                else:
                    device.key("KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_CENTER")
            else:
                device.click("XMLTV 地址（选填）")
            type_in_field(device, guide)
            close_keyboard(device)
        if television:
            device.key("KEYCODE_DPAD_DOWN")
            if guide is None:
                device.key("KEYCODE_DPAD_DOWN")
            device.choose_with_remote("保存", direction="KEYCODE_DPAD_RIGHT", limit=2)
        else:
            device.click("保存", "content-desc")
        device.wait_for("添加源", "content-desc")
        close_panels(device)

    def guide_rows(device):
        open_channels(device)
        if device.device_type == "tv":
            device.key("KEYCODE_DPAD_RIGHT")
        else:
            row = next(node for node in device.tree().iter("node")
                       if node.get("long-clickable") == "true" and find(node, "QA Release") is not None)
            left, top, right, bottom = bounds(row)
            x, y = str((left + right) // 2), str((top + bottom) // 2)
            device.input("swipe", x, y, x, y, "750")
        root = device.tree()
        assert any("今日节目单" in node.get("text", "") for node in root.iter("node"))
        rows = programme_rows(root)
        assert rows, "Guide cache is empty"
        device.key("KEYCODE_BACK")
        return sorted(rows)

    def refresh(device):
        close_panels(device)
        device.more()
        device.menu("刷新频道")
        open_channels(device)
        root = device.tree()
        assert find(root, "QA Release") is not None and find(root, "QA History") is not None
        return root

    def prepare(device):
        state = states[device.device_type]
        state["cacheGuide"] = "http://qa-iptv.test/release-guide.xml?cache=" + state["source"]["run"]
        edit_source(device, guide=state["cacheGuide"])
        open_channels(device)
        select_channel(device, "QA Release")
        state["guideRows"] = guide_rows(device)
        mark(device, "baseline")

    def offline(device):
        root = refresh(device)
        assert find(root, "更新失败 · 使用缓存") is not None
        mark(device, "503-playlist-retained")
        assert guide_rows(device) == states[device.device_type]["guideRows"]
        mark(device, "503-guide-retained")

    def recover(device):
        refresh(device)
        mark(device, "recovered")

    def isolation(device):
        state = states[device.device_type]
        edit_source(device, address="http://qa-iptv.test/debug-smoke.m3u?isolation=" + state["source"]["run"],
                    guide=guide_address(device))
        open_channels(device)
        root = device.tree()
        assert find(root, "QA Channel A") is not None and find(root, "QA Release") is None, "New playlist's first channel is missing or old channels leaked"
        assert not any("QA Now & Live" in node.get("text", "") for node in root.iter("node"))
        mark(device, "url-isolation")
        edit_source(device, address=state["source"]["playlist"], guide=guide_address(device))
        open_channels(device)
        root = device.tree()
        assert find(root, "QA Release") is not None and find(root, "QA Channel A") is None
        mark(device, "original-url-restored")
        close_panels(device)

    try:
        if not args.resume_isolation:
            parallel(prepare)
            since = time.time()
            urlopen(origin + "/control?mode=release-offline", timeout=5).read()
            parallel(offline)
            events = json.load(urlopen(origin + "/events", timeout=5))
            for state in states.values():
                for url in (state["source"]["playlist"], state["cacheGuide"]):
                    path = url.removeprefix("http://qa-iptv.test")
                    assert any(event["at"] >= since and event["path"] == path for event in events), path
            urlopen(origin + "/control?mode=ok", timeout=5).read()
            parallel(recover)
        parallel(isolation)
        for state in states.values():
            state.pop("failure", None)
            state.pop("failureTraceback", None)
    finally:
        urlopen(origin + "/control?mode=ok", timeout=5).read()
        output = QA_OUTPUT / "functional"
        output.mkdir(parents=True, exist_ok=True)
        for kind, state in states.items():
            (output / (kind + "-cache.json")).write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
