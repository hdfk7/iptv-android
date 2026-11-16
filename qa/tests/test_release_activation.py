import hashlib
import json
from pathlib import Path
import runpy
import tempfile
import unittest


activate = runpy.run_path(str(Path(__file__).resolve().parents[2] / "release-activate.py"))["activate"]


class ReleaseActivationTests(unittest.TestCase):
    def stage(self, directory, revision, digest=None):
        stage = directory / f".release-upload.{revision}"
        stage.mkdir()
        apk = stage / f"IPTV-release-{revision}.apk"
        apk.write_bytes(revision.encode())
        manifest = {"versionCode": 32, "versionName": "1.3.7", "apkUrl": apk.name,
                    "sha256": digest or hashlib.sha256(apk.read_bytes()).hexdigest()}
        (stage / "latest.json").write_text(json.dumps(manifest), encoding="utf-8")
        return stage

    def test_retains_previous_and_rolls_back_with_digest_verification(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            for revision in ("a", "b", "c"):
                activate(directory, self.stage(directory, revision))
            self.assertEqual({path.name for path in directory.glob("*.apk")},
                             {"IPTV-release-b.apk", "IPTV-release-c.apk"})
            self.assertEqual(activate(directory), "IPTV-release-b.apk")
            self.assertEqual(activate(directory), "IPTV-release-c.apk")
            (directory / "IPTV-release-b.apk").write_bytes(b"corrupt")
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                activate(directory)
            self.assertEqual(json.loads((directory / "latest.json").read_text())["apkUrl"], "IPTV-release-c.apk")

    def test_republishing_notes_for_same_apk_preserves_rollback(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            activate(directory, self.stage(directory, "a"))
            activate(directory, self.stage(directory, "b"))
            stage = self.stage(directory, "b")
            latest = stage / "latest.json"
            manifest = json.loads(latest.read_text(encoding="utf-8"))
            manifest["releaseNotes"] = "Corrected notes"
            latest.write_text(json.dumps(manifest), encoding="utf-8")
            activate(directory, stage)
            self.assertEqual(activate(directory), "IPTV-release-a.apk")

    def test_can_restore_good_release_when_active_apk_is_corrupt(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            activate(directory, self.stage(directory, "a"))
            activate(directory, self.stage(directory, "b"))
            (directory / "IPTV-release-b.apk").write_bytes(b"corrupt")
            self.assertEqual(activate(directory), "IPTV-release-a.apk")
            with self.assertRaisesRegex(ValueError, "No previous verified release"):
                activate(directory)

    def test_corrupt_staged_artifact_does_not_replace_active_release(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            activate(directory, self.stage(directory, "a"))
            latest = directory / "latest.json"
            before = latest.read_bytes()
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                activate(directory, self.stage(directory, "b", digest="invalid"))
            self.assertEqual(latest.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
