"""Drive controlled playback, cross-midnight guide and proxy checks through UI."""

import argparse
import io
import json
import re
import time
from urllib.request import urlopen

from PIL import Image, ImageChops

from release_ui import Device, bounds, find, tv_highlight, verify_release_apk
from upgrade_seed import (add_source, close_keyboard, close_panels, control, open_app,
                          open_channels, source_action, type_in_field)


def wait_for_requests(url, paths, since, timeout=45):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        with urlopen(url, timeout=3) as response:
            events = json.load(response)
        cursor = 0
        for event in events:
            if event["at"] >= since and event["path"] == paths[cursor]:
                cursor += 1
                if cursor == len(paths):
                    return
        time.sleep(0.5)
    raise AssertionError(f"Fixture did not see this request sequence: {paths}")


def verify_hls(device, origin_port):
    name = "IPTV-QA-HLS"
    origin = f"http://127.0.0.1:{origin_port}"
    add_source(device, name, origin + "/debug-smoke.m3u", origin + "/guide-data.xml")
    source_action(device, name)
    for _ in range(6):
        open_channels(device)
        if find(device.tree(), "QA Channel C") is not None:
            break
        close_panels(device)
        time.sleep(1)
    else:
        raise AssertionError("The HLS fixture channel did not load")
    since = time.time()
    device.choose_with_remote("QA Channel C", limit=8)
    wait_for_requests(origin + "/events", ["/stalling.m3u8", "/stall-start.ts", "/stall-tail.ts",
                                           "/media.mp4?smoke=c"], since, timeout=75)
    close_panels(device)
    verify_moving_picture(device)
    device.screenshot("tv", "hls-backup")


def verify_moving_picture(device):
    def picture():
        data = device.adb("exec-out", "screencap", "-p")
        with Image.open(io.BytesIO(data)) as image:
            width, height = image.size
            return image.convert("RGB").crop((width // 3, height // 3, width * 2 // 3, height * 2 // 3))

    first = picture()
    time.sleep(0.8)
    assert ImageChops.difference(first, picture()).getbbox() is not None, "Backup video stayed static"


def open_proxy(device):
    close_panels(device)
    device.more()
    device.menu("设置")
    if device.device_type == "tv":
        device.choose_with_remote("HTTP 代理", limit=1)
    else:
        device.click("HTTP 代理")


def enable_proxy(device, port):
    open_proxy(device)
    root = device.tree()
    assert control(root, "已关闭") is not None, "Start this test with the App proxy disabled"
    television = device.device_type == "tv"
    for label, value, direction in (("IP 地址", "127.0.0.1", "DOWN"), ("端口", str(port), "RIGHT")):
        if television:
            device.key("KEYCODE_DPAD_" + direction, "KEYCODE_DPAD_CENTER")
        else:
            device.click(label)
        type_in_field(device, value)
        close_keyboard(device)
    if television:
        device.key("KEYCODE_DPAD_UP", "KEYCODE_DPAD_CENTER")
        assert control(device.tree(), "已开启") is not None
        device.key("KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_DOWN")
        device.choose_with_remote("保存", limit=1)
    else:
        device.click("已关闭", "content-desc")
        assert control(device.tree(), "已开启") is not None
        device.click("保存", "content-desc")
    close_panels(device)


def proxy_playlist(device, origin_port, guide_url, since):
    host = "http://qa-iptv.test"
    playlist = f"/release.m3u?device={device.device_type}"
    wait_for_requests(f"http://127.0.0.1:{origin_port}/events",
                      [host + playlist, playlist], since)
    wait_for_requests(f"http://127.0.0.1:{origin_port}/events",
                      [guide_url, guide_url.removeprefix(host)], since)
    wait_for_requests(f"http://127.0.0.1:{origin_port}/events",
                      [host + "/media.mp4?release=1", "/media.mp4?release=1"], since)


def programme_rows(root):
    rows = set()
    for row in root.iter("node"):
        # Compose may expose the row as merged semantics or as separate text cells.
        for value in (row.get("text"), row.get("content-desc")):
            match = re.fullmatch(r"(\d{2}:\d{2})(?:\s*,\s*|\s+)(\S.*)", value or "", re.S)
            if match:
                rows.add(match.groups())
        cells = [item.get("text") for item in row.iter("node") if item.get("text")]
        if len(cells) == 2 and re.fullmatch(r"\d{2}:\d{2}", cells[0]):
            rows.add(tuple(cells))
        # Some phone accessibility trees flatten each row into sibling text nodes.
        siblings = [child for child in row.findall("node") if child.get("text")]
        for clock, title in zip(siblings, siblings[1:]):
            if not re.fullmatch(r"\d{2}:\d{2}", clock.get("text")) or not clock.get("bounds") or not title.get("bounds"):
                continue
            _, top, right, bottom = bounds(clock)
            title_left, title_top, _, title_bottom = bounds(title)
            if title_left >= right and min(bottom, title_bottom) > max(top, title_top):
                rows.add((clock.get("text"), title.get("text")))
    return rows


def verify_midnight(device):
    open_channels(device)
    root = device.tree()
    if device.device_type == "tv":
        assert "QA Release" in tv_highlight(device, root)
        device.key("KEYCODE_DPAD_RIGHT")
    else:
        row = next(item for item in root.iter("node")
                   if item.get("long-clickable") == "true" and find(item, "QA Release") is not None)
        left, top, right, bottom = bounds(row)
        x, y = str((left + right) // 2), str((top + bottom) // 2)
        device.input("swipe", x, y, x, y, "750")
    root = device.tree()
    assert any("今日节目单" in node.get("text", "") for node in root.iter("node"))
    seen = set()

    def collect():
        seen.update(programme_rows(device.tree()))

    collect()
    if device.device_type == "tv":
        for key, steps in (("UP", 6), ("DOWN", 8)):
            for _ in range(steps):
                device.key("KEYCODE_DPAD_" + key)
                collect()
    else:
        panel = next((n for n in root.iter("node") if n.get("scrollable") == "true"), None)
        if panel is not None:
            left, top, right, bottom = bounds(panel)
            x = str((left + right) // 2)
            for start, end in ((top + 20, bottom - 20), (bottom - 20, top + 20)):
                device.input("swipe", x, str(start), x, str(end), "550")
                collect()
    assert any(time == "00:00" and title.endswith("QA Entering Midnight") for time, title in seen), seen
    assert any(time == "23:00" and title.endswith("QA Leaving Midnight") for time, title in seen), seen
    assert not any("QA Yesterday Only" in title or "QA Tomorrow Only" in title for _, title in seen), seen
    assert sum(title.startswith("正在播") for _, title in seen) == 1, seen
    device.screenshot(device.device_type, "midnight-guide")
    device.key("KEYCODE_BACK")


def guide_address(device):
    reading = device.shell("date", "+%Y-%m-%dT%z").strip()
    match = re.fullmatch(r"(\d{4}-\d{2}-\d{2})T([+-]\d{4})", reading)
    assert match is not None, reading
    day, offset = match.groups()
    minutes = int(offset[1:3]) * 60 + int(offset[3:])
    if offset[0] == "-":
        minutes = -minutes
    return f"http://qa-iptv.test/midnight.xml?offset={minutes}&device={device.device_type}&date={day}"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--device", choices=("phone", "tv"), required=True)
    parser.add_argument("--phase", choices=("hls", "proxy-guide", "after-restart"), required=True)
    parser.add_argument("--origin-port", type=int, default=18764)
    parser.add_argument("--proxy-port", type=int, default=18766)
    parser.add_argument("--old-pid", help="PID recorded after proxy-guide, before closing the App from the system UI")
    args = parser.parse_args()
    if args.phase == "hls" and args.device != "tv":
        parser.error("The HLS case uses a TV remote")
    if args.phase == "after-restart" and not args.old_pid:
        parser.error("--old-pid is required to verify a cold restart")
    device = Device(args.serial, args.device)
    verify_release_apk(device)
    open_app(device)
    if args.phase == "hls":
        verify_hls(device, args.origin_port)
    else:
        name = "IPTV-QA-Proxy"
        guide = guide_address(device)
        playlist = f"http://qa-iptv.test/release.m3u?device={args.device}"
        if args.phase == "proxy-guide":
            enable_proxy(device, args.proxy_port)
            add_source(device, name, playlist, guide)
            since = time.time()
            source_action(device, name)
            proxy_playlist(device, args.origin_port, guide, since)
            verify_midnight(device)
            print("Record this PID before closing the App from the system UI:",
                  device.shell("pidof", "cn.hdfk7.android.iptv").strip())
        else:
            pid = device.shell("pidof", "cn.hdfk7.android.iptv").strip()
            assert pid and pid != args.old_pid, "The App is still running in the original process"
            open_proxy(device)
            root = device.tree()
            assert all(control(root, label) is not None for label in ("已开启", "127.0.0.1", str(args.proxy_port)))
            device.screenshot(device.device_type, "proxy-after-restart")
            since = time.time()
            source_action(device, name)
            device.more()
            device.menu("刷新频道")
            wait_for_requests(f"http://127.0.0.1:{args.origin_port}/events",
                              [playlist, playlist.removeprefix("http://qa-iptv.test")], since)
    print(f"{args.device}: {args.phase} passed", flush=True)


if __name__ == "__main__":
    main()
