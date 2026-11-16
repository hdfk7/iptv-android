"""Activate a staged IPTV release or restore the previous verified manifest under the deployment lock."""

import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile


def release_name(manifest):
    name = manifest.get("apkUrl", "")
    if not isinstance(name, str) or not re.fullmatch(r"IPTV-release-[A-Za-z0-9._-]+\.apk", name):
        raise ValueError("Invalid release APK name")
    if (type(manifest.get("versionCode")) is not int or manifest["versionCode"] <= 0
            or not isinstance(manifest.get("versionName"), str) or not manifest["versionName"].strip()):
        raise ValueError("Invalid release version")
    return name


def release_file(manifest, directory):
    apk = directory / release_name(manifest)
    if apk.stat().st_size == 0:
        raise ValueError(f"Empty release APK: {apk.name}")
    digest = hashlib.sha256()
    with apk.open("rb") as stream:
        for block in iter(lambda: stream.read(128 * 1024), b""):
            digest.update(block)
    actual = digest.hexdigest()
    expected = manifest.get("sha256")
    if expected is not None and (not isinstance(expected, str) or expected.lower() != actual):
        raise ValueError(f"SHA-256 mismatch: {apk.name}")
    return apk, actual


def read_release(path, directory):
    manifest = json.loads(path.read_text(encoding="utf-8"))
    apk, digest = release_file(manifest, directory)
    # Older releases predate manifest digests. Save a digest when recording them for rollback.
    return {**manifest, "sha256": digest}, apk


def remove_if_present(path):
    try:
        path.unlink()
    except FileNotFoundError:
        pass


def write_manifest(path, manifest):
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                     prefix=".release-manifest-", delete=False) as temporary:
        pending = Path(temporary.name)
        try:
            json.dump(manifest, temporary, ensure_ascii=True, indent=2)
            temporary.write("\n")
            temporary.close()
            pending.chmod(0o644)
            pending.replace(path)
        finally:
            remove_if_present(pending)


def activate(directory, stage=None):
    latest_path = directory / "latest.json"
    current = None
    current_apk = None
    if latest_path.exists():
        manifest = json.loads(latest_path.read_text(encoding="utf-8"))
        current_apk = directory / release_name(manifest)
        try:
            current, _ = read_release(latest_path, directory)
        except (OSError, ValueError):
            if stage is not None:
                raise
    if stage is None:
        if current_apk is None:
            raise ValueError("No active release is recorded")
        previous_path = directory / f".previous-{current_apk.name}.json"
        if not previous_path.exists():
            raise ValueError("No previous verified release is recorded")
        target, target_apk = read_release(previous_path, directory)
    else:
        target, staged_apk = read_release(stage / "latest.json", stage)
        target_apk = directory / staged_apk.name
        if target_apk.exists():
            release_file(target, directory)
        else:
            staged_apk.replace(target_apk)
    if current != target:
        # Prepare the rollback record for the new active APK before switching latest.json.
        previous_record = directory / f".previous-{target_apk.name}.json"
        if current is not None and current_apk != target_apk:
            write_manifest(previous_record, current)
        elif current is None:
            remove_if_present(previous_record)
        write_manifest(latest_path, target)
    if stage is not None:
        remove_if_present(stage / target_apk.name)
        (stage / "latest.json").unlink()
        stage.rmdir()
        if current_apk != target_apk:
            keep = {current_apk, target_apk}
            for apk in directory.glob("IPTV-release-*.apk"):
                if apk not in keep:
                    apk.unlink()
                    remove_if_present(directory / f".previous-{apk.name}.json")
    return target_apk.name


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: release-activate.py .release-upload.<id>|rollback")
    arg = sys.argv[1]
    if arg != "rollback" and not re.fullmatch(r"\.release-upload\.[A-Za-z0-9]+", arg):
        sys.exit("Invalid release staging directory")
    print(activate(Path.cwd(), None if arg == "rollback" else Path(arg)))
