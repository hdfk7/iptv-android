"""Verify channel shortcuts, manual line changes and App volume actions."""

import argparse
import json
import re
import time

from controlled_ui import verify_moving_picture, wait_for_requests
from release_ui import Device, QA_OUTPUT, find, verify_release_apk
from upgrade_seed import close_panels, open_channels, select_channel, source_action, open_app


def parse_volume(text):
    match = re.search(r"volume is (\d+) in range \[\d+\.\.(\d+)\]", text)
    if match:
        return tuple(map(int, match.groups()))
    music = re.search(r"(?ms)^- STREAM_MUSIC:\s*(.*?)(?=^- STREAM_|\Z)", text)
    assert music is not None, "Music volume readout is unavailable"
    maximum = re.search(r"Max:\s*(\d+)", music[1])
    current = re.search(r"\(speaker\):\s*(\d+)", music[1])
    assert maximum is not None and current is not None, "Active speaker volume is unavailable"
    return int(current[1]), int(maximum[1])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--device", choices=("phone", "tv"), required=True)
    parser.add_argument("--origin-port", type=int, default=18764)
    parser.add_argument("--source")
    args = parser.parse_args()
    device = Device(args.serial, args.device)
    _, checksum = verify_release_apk(device)
    open_app(device)
    television = args.device == "tv"
    results = []
    source = args.source
    if source is None:
        prepared = json.loads((QA_OUTPUT / "controlled" / (args.device + "-proxy-source.json")).read_text(encoding="utf-8"))
        assert prepared["serial"] == args.serial
        source = prepared["name"]

    def mark(label):
        device.screenshot(args.device, "menu-" + label)
        results.append(label)
        print(label + " passed", flush=True)

    def menu_action(label):
        close_panels(device)
        device.more()
        device.menu(label)

    def assert_title(title):
        close_panels(device)
        if television:
            device.key("KEYCODE_INFO")
        else:
            device.player_control("更多")
        assert find(device.tree(), title) is not None, f"Channel did not change to {title}"

    def volume():
        text = device.shell("cmd", "media_session", "volume", "--stream", "3", "--get")
        return parse_volume(text or device.shell("dumpsys", "audio"))

    try:
        source_action(device, source)
        open_channels(device)
        select_channel(device, "QA Release")
        close_panels(device)
        if television:
            device.key("KEYCODE_DPAD_UP")
        else:
            menu_action("下一台")
        assert_title("QA History")
        mark("channel-next-or-up")
        if television:
            device.key("KEYCODE_DPAD_DOWN")
        else:
            menu_action("上一台")
        assert_title("QA Release")
        mark("channel-previous-or-down")
        for action in ("上一条线路", "下一条线路"):
            since = time.time()
            menu_action(action)
            wait_for_requests(f"http://127.0.0.1:{args.origin_port}/events",
                              ["/fail-release.mp4", "/media.mp4?release=1"], since)
            close_panels(device)
            verify_moving_picture(device)
            mark(action)
        original, maximum = volume()
        assert maximum > 0
        first, second = ("音量 ＋", "音量 −") if original == 0 else ("音量 −", "音量 ＋")
        menu_action(first)
        changed, _ = volume()
        assert changed != original, "App volume button had no effect"
        mark("volume-changed")
        menu_action(second)
        restored, _ = volume()
        assert restored == original, "Volume did not return to its original level"
        mark("volume-restored")
        close_panels(device)
    finally:
        output = QA_OUTPUT / "functional"
        output.mkdir(parents=True, exist_ok=True)
        (output / (args.device + "-menu.json")).write_text(
            json.dumps({"sha256": checksum, "passedSteps": results}, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
