"""Run the IPTV playback and EPG smoke test using a temporary Debug install."""
import argparse
import json
import re
import subprocess
import threading
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from fixture_server import FixtureServer

PACKAGE = "cn.hdfk7.android.iptv.debug"
ACTIVITY = f"{PACKAGE}/cn.hdfk7.android.iptv.app.MainActivity"


def wait_until(check, label, timeout=25):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = check()
        if result:
            return result
        time.sleep(0.4)
    raise AssertionError(label)


def run(serial, media):
    def adb(*args, input_data=None):
        return subprocess.run(
            ["adb", "-s", serial, *args], input=input_data, check=True,
            capture_output=True, text=True, encoding="utf-8", timeout=45,
        ).stdout

    assert media.is_file() and media.with_suffix(".ts").is_file(), "Provide matching MP4 and TS fixtures"
    assert f"package:{PACKAGE}" not in adb("shell", "pm", "list", "packages", PACKAGE).splitlines(), (
        "Use a device without an existing IPTV Debug install"
    )
    apk_dir = Path("app/build/outputs/apk/debug")
    metadata = json.loads((apk_dir / "output-metadata.json").read_text(encoding="utf-8"))["elements"][0]
    server = FixtureServer(("127.0.0.1", 0), media)
    port = f"tcp:{server.server_port}"
    address = f"http://127.0.0.1:{server.server_port}/debug-smoke.m3u"
    threading.Thread(target=server.serve_forever, daemon=True).start()
    installed = False
    reversed_port = False

    def app_file(path, data):
        adb("shell", "-T", f"run-as {PACKAGE} sh -c 'cat > {path}'", input_data=data)

    def start():
        adb("shell", "am", "start", "-W", "-n", ACTIVITY)
        return wait_until(lambda: adb("shell", "pidof", PACKAGE).strip(), "IPTV process missing")

    def stop():
        adb("shell", "am", "force-stop", PACKAGE)

    def frames(pid):
        logs = adb("logcat", "-d", "--pid", pid, "-s", "IptvPlayback:D")
        return re.findall(r"\bfirst_frame_ms=(\d+) tune_ms=(\d+) recovery_ms=(\d+) first_line=(true|false)", logs)

    def paths():
        with server.event_lock:
            return [event["path"] for event in server.events]

    def input_key(key):
        adb("shell", "input", "keyevent", key)

    def hierarchy():
        report = adb("shell", "uiautomator", "dump", "/sdcard/iptv-debug-smoke.xml")
        try:
            return ET.fromstring(adb("shell", "cat", "/sdcard/iptv-debug-smoke.xml"))
        except subprocess.CalledProcessError as error:
            raise AssertionError(f"UI hierarchy unavailable: {report} {error.stderr}") from error

    def tap_bounds(node):
        left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
        adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def open_channels():
        if "android.software.leanback" in adb("shell", "pm", "list", "features"):
            time.sleep(6)
            input_key("KEYCODE_DPAD_LEFT")
            return
        root = hierarchy()
        button = next((node for node in root.iter("node") if node.get("text") == "\u9891\u9053"), None)
        if button is None:
            tap_bounds(root.find("node"))
            root = hierarchy()
            button = next(node for node in root.iter("node") if node.get("text") == "\u9891\u9053")
        tap_bounds(button)

    def visible_guide():
        titles = [node.get("text", "") for node in hierarchy().iter("node")]
        return any("QA Now & Live" in title for title in titles) and any("QA Next" in title for title in titles)

    try:
        adb("reverse", port, port)
        reversed_port = True
        adb("install", str(apk_dir / metadata["outputFile"]))
        installed = True
        package = adb("shell", "dumpsys", "package", PACKAGE)
        assert f'versionCode={metadata["versionCode"]} ' in package and "DEBUGGABLE" in package
        adb("shell", "run-as", PACKAGE, "mkdir", "-p", "files", "shared_prefs")
        library = {
            "version": 1, "activeSourceId": "fixture",
            "sources": [
                {"id": "other", "name": "Other", "url": address.replace("debug-smoke", "other")},
                {"id": "fixture", "name": "QA Source", "url": address},
            ],
            "favorites": [],
        }
        app_file("files/library.json", json.dumps(library))
        app_file("shared_prefs/viewing.xml", '<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map><boolean name="resume" value="true" /></map>')
        pid = start()
        first = wait_until(lambda: frames(pid), "404 did not recover")
        assert first[0][3] == "false", (first, paths())
        assert "/fail-a.mp4" in paths() and "/media.mp4?smoke=a" in paths(), (first, paths())
        print("404 recovery:", first[0], flush=True)
        wait_until(lambda: "/guide.xml" in paths(), "EPG was not requested")
        time.sleep(6)
        input_key("KEYCODE_MEDIA_PAUSE")
        open_channels()
        wait_until(visible_guide, "Current/next programmes did not appear")
        input_key("KEYCODE_BACK")
        input_key("KEYCODE_MEDIA_PLAY")

        stop()
        start_at = len(paths())
        server.mode = "http503"
        pid = start()
        resumed = wait_until(lambda: frames(pid), "Cached channel did not resume")
        assert resumed[0][3] == "true" and "/fail-a.mp4" not in paths()[start_at:]
        assert "/media.mp4?smoke=a" in paths()[start_at:]
        print("Offline catalog and persisted ranking:", resumed[0], flush=True)

        input_key("KEYCODE_CHANNEL_UP")
        wait_until(lambda: "/media.mp4?smoke=b" in paths() and len(frames(pid)) >= 2, "Channel B did not play")
        stop()
        start_at = len(paths())
        pid = start()
        wait_until(lambda: "/media.mp4?smoke=b" in paths()[start_at:] and frames(pid), "Channel B was not restored")
        print("Cold-start source and channel restored", flush=True)

        input_key("KEYCODE_CHANNEL_UP")
        wait_until(lambda: "/stall-tail.ts" in paths(), "Stall fixture was not reached")
        recovered = wait_until(
            lambda: frames(pid) if "/media.mp4?smoke=c" in paths() and len(frames(pid)) >= 3 else None,
            "Stalled playback did not recover",
        )
        assert recovered[-2][3] == "true" and recovered[-1][3] == "false"
        print("Playback stall recovery:", recovered[-1], flush=True)
    finally:
        try:
            if installed:
                adb("shell", "rm", "-f", "/sdcard/iptv-debug-smoke.xml")
                adb("uninstall", PACKAGE)
        finally:
            if reversed_port:
                adb("reverse", "--remove", port)
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--media", type=Path, required=True)
    args = parser.parse_args()
    run(args.serial, args.media)
