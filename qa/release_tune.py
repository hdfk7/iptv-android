"""Measure alternating channel switches in the installed, signed Release APK."""
import argparse
import json
import math
import re
import statistics
import subprocess
import time
from pathlib import Path
from release_ui import Device, PACKAGE, release_artifact, verify_release_apk

TAG = "IptvPlayback:D"
EVENT = re.compile(r"first_frame_ms=\d+ tune_ms=(\d+) recovery_ms=\d+ first_line=(true|false) tune_id=(\d+)|tune_failed_id=(\d+)")


def measure(serial, rounds, timeout):
    def adb(*args):
        return subprocess.check_output(["adb", "-s", serial, *args], timeout=20, text=True, encoding="utf-8")

    _, apk = release_artifact()
    device = Device(serial, "tv")
    verify_release_apk(device)
    device.start()
    pid = adb("shell", "pidof", PACKAGE).strip()

    def events():
        return EVENT.findall(adb("logcat", "-d", "--pid", pid, "-s", TAG))

    def wait_for_tune(after, request_play=False):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            for latency, first_line, tune_id, failed_id in events():
                if int(tune_id or failed_id) > after:
                    return int(tune_id or failed_id), int(latency) if latency else None, first_line == "true"
            if request_play:
                adb("shell", "input", "keyevent", "KEYCODE_MEDIA_PLAY")
                time.sleep(1)
            else:
                time.sleep(0.3)
        return None

    startup = wait_for_tune(-1, request_play=True)
    assert startup is not None and startup[1] is not None, (
        "Initial channel did not render or APP_DEBUG_SWITCH is disabled in this Release build"
    )
    samples = []
    warmup = 4
    for index in range(rounds + warmup):
        before = events()
        last_id = max(int(tune_id or failed_id) for _, _, tune_id, failed_id in before)
        direction = "KEYCODE_CHANNEL_UP" if index % 2 == 0 else "KEYCODE_CHANNEL_DOWN"
        adb("shell", "input", "keyevent", direction)
        tune = wait_for_tune(last_id)
        if index >= warmup:
            samples.append({
                "direction": direction,
                "first_frame_ms": tune[1] if tune is not None else None,
                "first_line": tune[2] if tune is not None else False,
                "status": "success" if tune and tune[1] is not None else "failed" if tune else "timeout",
            })
        if (index + 1) % 10 == 0:
            print(f"{index + 1}/{rounds + warmup} switches", flush=True)

    successful = sorted(row["first_frame_ms"] for row in samples if row["status"] == "success")
    result = {
        "device": adb("shell", "getprop", "ro.product.model").strip(),
        "android": adb("shell", "getprop", "ro.build.version.release").strip(),
        "apk": apk.name,
        "warmup_switches": warmup,
        "measured_switches": rounds,
        "successes": len(successful),
        "failed": sum(row["status"] == "failed" for row in samples),
        "timeouts": sum(row["status"] == "timeout" for row in samples),
        "p50_ms": statistics.median(successful) if successful else None,
        "p95_ms": successful[math.ceil(.95 * len(successful)) - 1] if successful else None,
        "max_ms": max(successful, default=None),
        "samples": samples,
    }
    output = Path("app/build/outputs/qa")
    output.mkdir(parents=True, exist_ok=True)
    name = re.sub(r"[^a-zA-Z0-9_-]", "_", serial)
    (output / f"release-tune-{name}.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "samples"}), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--rounds", type=int, default=40)
    parser.add_argument("--timeout", type=float, default=20)
    args = parser.parse_args()
    if args.rounds < 20 or args.timeout <= 0:
        parser.error("--rounds must be at least 20 and --timeout must be positive")
    measure(args.serial, args.rounds, args.timeout)
