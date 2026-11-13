"""Measure alternating channel switches in the installed, signed Release APK."""
import argparse
import json
import math
import re
import statistics
import subprocess
import time
from release_ui import QA_OUTPUT, Device, PACKAGE, release_artifact, verify_release_apk
from upgrade_seed import open_app

TAG = "IptvPlayback:D"
EVENT = re.compile(r"first_frame_ms=\d+ tune_ms=(\d+) recovery_ms=(\d+) first_line=(true|false) tune_id=(\d+)|tune_failed_id=(\d+)")
START = re.compile(r"tune_start_id=(\d+)")
PREVIEW = re.compile(r"preview_copies=(\d+) preview_ms=(\d+) standby=false preview_tune_id=(\d+)")


def parse_tunes(log):
    return {int(tune_id or failed_id): {
        "first_frame_ms": int(latency) if latency else None,
        "recovery_ms": int(recovery) if recovery else None,
        "first_line": first_line == "true",
        "status": "success" if latency else "failed",
    } for latency, recovery, first_line, tune_id, failed_id in EVENT.findall(log)}


def summarize(values):
    values = sorted(values)
    return {
        "p50_ms": statistics.median(values) if values else None,
        "p95_ms": values[math.ceil(.95 * len(values)) - 1] if values else None,
        "max_ms": max(values, default=None),
    }


def measure(serial, rounds, timeout, device_type="tv"):
    def adb(*args):
        return subprocess.check_output(["adb", "-s", serial, *args], timeout=20, text=True, encoding="utf-8")

    _, apk = release_artifact()
    device = Device(serial, device_type)
    _, checksum = verify_release_apk(device)
    open_app(device)
    pid = adb("shell", "pidof", PACKAGE).strip()
    assert pid.isdigit(), "The IPTV process did not start"

    def read_log():
        return adb("logcat", "-d", "--pid", pid, "-s", TAG)

    def wait_for_tune(after, request_play=False):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            log = read_log()
            events = parse_tunes(log)
            starts = [int(value) for value in START.findall(log) if int(value) > after]
            if starts and starts[0] in events:
                event = events[starts[0]]
                if not request_play or event["status"] == "success":
                    return starts[0], event
            if not starts:
                for tune_id, event in events.items():
                    if tune_id > after and (not request_play or event["status"] == "success"):
                        return tune_id, event
            if request_play:
                adb("shell", "input", "keyevent", "KEYCODE_MEDIA_PLAY")
                time.sleep(1)
            else:
                time.sleep(0.3)
        return None

    startup = next(((tune_id, event) for tune_id, event in reversed(list(parse_tunes(read_log()).items()))
                    if event["status"] == "success"), None)
    if startup is None:
        startup = wait_for_tune(-1, request_play=True)
    assert startup is not None and startup[1]["status"] == "success", (
        "Initial channel did not render or APP_DEBUG_SWITCH is disabled in this Release build"
    )
    samples = []
    warmup = 4
    for index in range(rounds + warmup):
        log = read_log()
        last_id = max([startup[0], *parse_tunes(log), *map(int, START.findall(log))])
        direction = "KEYCODE_CHANNEL_UP" if index % 2 == 0 else "KEYCODE_CHANNEL_DOWN"
        adb("shell", "input", "keyevent", direction)
        tune = wait_for_tune(last_id)
        if index >= warmup:
            tune_id, event = tune or (None, {
                "first_frame_ms": None, "recovery_ms": None, "first_line": False, "status": "timeout",
            })
            samples.append({"direction": direction, "tune_id": tune_id, **event})
        if (index + 1) % 10 == 0:
            print(f"{index + 1}/{rounds + warmup} switches", flush=True)

    # The preview scan can finish after the first-frame event.
    time.sleep(2.2)
    previews = {}
    for copies, duration, tune_id in PREVIEW.findall(read_log()):
        previews.setdefault(int(tune_id), []).append((int(copies), int(duration)))
    for row in samples:
        captures = previews.get(row["tune_id"], [])
        row["preview_copies"] = sum(copies for copies, _ in captures) if captures else None
        row["preview_ms"] = sum(duration for _, duration in captures) if captures else None
    successful = [row["first_frame_ms"] for row in samples if row["status"] == "success"]
    first_line = sum(row["first_line"] for row in samples)
    recovered = [row["recovery_ms"] for row in samples if row["status"] == "success" and not row["first_line"]]
    result = {
        "device": adb("shell", "getprop", "ro.product.model").strip(),
        "android": adb("shell", "getprop", "ro.build.version.release").strip(),
        "apk": apk.name,
        "sha256": checksum,
        "warmup_switches": warmup,
        "measured_switches": rounds,
        "successes": len(successful),
        "failed": sum(row["status"] == "failed" for row in samples),
        "timeouts": sum(row["status"] == "timeout" for row in samples),
        **summarize(successful),
        "first_line_successes": first_line,
        "first_line_success_rate": first_line / rounds,
        "recoveries": len(recovered),
        "recovery": summarize(recovered),
        "preview": summarize(row["preview_ms"] for row in samples if row["preview_ms"] is not None),
        "samples": samples,
    }
    output = QA_OUTPUT
    output.mkdir(parents=True, exist_ok=True)
    name = re.sub(r"[^a-zA-Z0-9_-]", "_", serial)
    (output / f"release-tune-{name}.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "samples"}), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--device", choices=("tv", "phone"), default="tv")
    parser.add_argument("--rounds", type=int, default=40)
    parser.add_argument("--timeout", type=float, default=20)
    args = parser.parse_args()
    if args.rounds < 20 or args.timeout <= 0:
        parser.error("--rounds must be at least 20 and --timeout must be positive")
    measure(args.serial, args.rounds, args.timeout, args.device)
