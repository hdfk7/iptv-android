"""Measure ordinary channel switches or forced line recovery on a signed APK."""

import sys

# Set this before importing QA modules, including when run without -B.
sys.dont_write_bytecode = True

import argparse
import json
import math
import re
import statistics
import subprocess
import time
from urllib.parse import urlsplit
from urllib.request import urlopen

from release_ui import QA_OUTPUT, Device, PACKAGE, release_artifact, verify_release_apk
from upgrade_seed import close_panels, open_app

TAG = "IptvPlayback:D"
EVENT = re.compile(r"first_frame_ms=\d+ tune_ms=(\d+) recovery_ms=(\d+) first_line=(true|false) tune_id=(\d+)|tune_failed_id=(\d+)")
START = re.compile(r"tune_start_id=(\d+)")
PREVIEW = re.compile(r"preview_copies=(\d+) preview_ms=(\d+) standby=false preview_tune_id=(\d+)")


def parse_tunes(log):
    events = {}
    for latency, recovery, first_line, tune_id, failed_id in EVENT.findall(log):
        if tune_id:
            # A later attempt can also render within the same tune.
            events.setdefault(int(tune_id), {
                "first_frame_ms": int(latency),
                "recovery_ms": int(recovery),
                "first_line": first_line == "true",
                "status": "success",
                "failed_after_first_frame": False,
            })
        else:
            event = events.setdefault(int(failed_id), {
                "first_frame_ms": None,
                "recovery_ms": None,
                "first_line": False,
                "status": "failed",
                "failed_after_first_frame": False,
            })
            if event["status"] == "success":
                event["failed_after_first_frame"] = True
    return events


def summarize(values):
    values = sorted(values)
    return {
        "p50_ms": statistics.median(values) if values else None,
        "p95_ms": values[math.ceil(.95 * len(values)) - 1] if values else None,
        "max_ms": max(values, default=None),
    }


def saw_recovery(events, run, number):
    failure = f"/fail-tune-{run}-{number}.mp4"
    backup = f"/media.mp4?recovery={run}-{number}"
    failed_first = False
    for event in events:
        url = urlsplit(event["path"])
        path = url.path + (f"?{url.query}" if url.query else "")
        if path == failure:
            failed_first = True
        elif path == backup:
            return failed_first
    return False


def measure(serial, rounds, timeout, device_type="tv", recovery_run=None, fixture_port=18764):
    def adb(*args):
        return subprocess.check_output(["adb", "-s", serial, *args], timeout=20, text=True, encoding="utf-8")

    _, apk = release_artifact()
    device = Device(serial, device_type)
    _, checksum = verify_release_apk(device)
    open_app(device)
    if recovery_run:
        close_panels(device)
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
    warmup = 0 if recovery_run else 4
    for index in range(rounds + warmup):
        log = read_log()
        last_id = max([startup[0], *parse_tunes(log), *map(int, START.findall(log))])
        direction = "KEYCODE_CHANNEL_UP" if recovery_run or index % 2 == 0 else "KEYCODE_CHANNEL_DOWN"
        requested_at = time.time()
        adb("shell", "input", "keyevent", direction)
        tune = wait_for_tune(last_id)
        if index >= warmup:
            tune_id, event = tune or (None, {
                "first_frame_ms": None, "recovery_ms": None, "first_line": False, "status": "timeout",
                "failed_after_first_frame": False,
            })
            samples.append({"direction": direction, "tune_id": tune_id, "requested_at": requested_at, **event})
        if (index + 1) % 10 == 0:
            print(f"{index + 1}/{rounds + warmup} switches", flush=True)

    # The preview scan can finish after the first-frame event.
    time.sleep(2.2)
    previews = {}
    final_log = read_log()
    final_events = parse_tunes(final_log)
    for copies, duration, tune_id in PREVIEW.findall(final_log):
        previews.setdefault(int(tune_id), []).append((int(copies), int(duration)))
    fixture_events = []
    if recovery_run:
        with urlopen(f"http://127.0.0.1:{fixture_port}/events", timeout=5) as response:
            fixture_events = json.load(response)
    for number, row in enumerate(samples, start=2):
        if recovery_run:
            row["verified_recovery"] = (
                row["status"] == "success" and not row["first_line"] and
                row["recovery_ms"] > 0 and saw_recovery(
                    (event for event in fixture_events if event["at"] >= row["requested_at"]),
                    recovery_run, number
                )
            )
        event = final_events.get(row["tune_id"])
        if event and row["status"] == "success":
            row["failed_after_first_frame"] |= event["failed_after_first_frame"]
        captures = previews.get(row["tune_id"], [])
        row["preview_copies"] = sum(copies for copies, _ in captures) if captures else None
        row["preview_ms"] = sum(duration for _, duration in captures) if captures else None
    successful = [row["first_frame_ms"] for row in samples if row["status"] == "success"]
    first_line = sum(row["first_line"] for row in samples)
    recovered = [row["recovery_ms"] for row in samples if (
        row["verified_recovery"] if recovery_run else row["status"] == "success" and not row["first_line"]
    )]
    result = {
        "device": adb("shell", "getprop", "ro.product.model").strip(),
        "mode": "recovery" if recovery_run else "standard",
        "android": adb("shell", "getprop", "ro.build.version.release").strip(),
        "apk": apk.name,
        "sha256": checksum,
        "warmup_switches": warmup,
        "measured_switches": rounds,
        "successes": len(successful),
        "failed": sum(row["status"] == "failed" for row in samples),
        "timeouts": sum(row["status"] == "timeout" for row in samples),
        "failures_after_first_frame": sum(row["failed_after_first_frame"] for row in samples),
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
    suffix = "-recovery" if recovery_run else ""
    (output / f"release-tune-{name}{suffix}.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "samples"}), flush=True)

    if recovery_run and len(recovered) != rounds:
        raise AssertionError(f"Verified {len(recovered)}/{rounds} forced recoveries. Check the per-switch report.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--device", choices=("tv", "phone"), default="tv")
    parser.add_argument("--rounds", type=int, default=40)
    parser.add_argument("--timeout", type=float, default=20)
    parser.add_argument("--recovery-run", help="Unique run identifier used in /tune-recovery.m3u?run=...")
    parser.add_argument("--fixture-port", type=int, default=18764)
    args = parser.parse_args()
    if args.rounds < 20 or args.timeout <= 0:
        parser.error("--rounds must be at least 20 and --timeout must be positive")
    if args.recovery_run and (args.rounds > 99 or not re.fullmatch(r"[A-Za-z0-9_-]{1,24}", args.recovery_run)):
        parser.error("Recovery mode needs a valid run identifier and 20–99 rounds")
    measure(args.serial, args.rounds, args.timeout, args.device, args.recovery_run, args.fixture_port)
