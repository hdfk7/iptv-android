"""Offline regressions for QA bugs; never operate an Android device."""

import hashlib
import io
import json
import sys
import tempfile
import threading
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import Mock, patch
from urllib.request import urlopen

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import release_ui
from controlled_ui import guide_address, programme_rows
from fixture_server import FixtureServer
from local_upgrade import finish_installation
from release_tune import parse_tunes, summarize
from upgrade_seed import type_in_field


class UiRegressionTests(unittest.TestCase):
    def test_tv_launch_opens_apps_before_selecting_iptv(self):
        device = release_ui.Device("offline-test", "tv")
        device.key = Mock()
        device.launcher_select = Mock()
        device.wait_for_launcher_apps = Mock()
        device.wait_for_app_focus = Mock()
        device.start()
        device.key.assert_called_once_with("KEYCODE_HOME")
        self.assertEqual([c.args for c in device.launcher_select.call_args_list], [("应用",), ("IPTV",)])

    def test_selected_launcher_entry_is_confirmed_without_moving(self):
        device = release_ui.Device("offline-test", "tv")
        device.key = Mock()
        device.tree = Mock(return_value=ET.fromstring(
            '<hierarchy><node focused="true"><node content-desc="IPTV"/></node></hierarchy>'))
        device.launcher_select("IPTV")
        device.key.assert_called_once_with("KEYCODE_DPAD_CENTER")

    def test_stale_launcher_focus_stops_after_repeated_geometry(self):
        device = release_ui.Device("offline-test", "tv")
        device.key = Mock()
        device.screenshot = Mock(return_value="focus.png")
        device.adb = Mock()
        device.tree = Mock(return_value=ET.fromstring(
            '<hierarchy><node focused="true" bounds="[0,0][30,30]"/>'
            '<node text="应用" bounds="[60,0][90,30]"/></hierarchy>'))
        with patch("release_ui.launcher_apps_highlight", return_value=False):
            with self.assertRaisesRegex(AssertionError, "focus.png"):
                device.launcher_select("应用")
        self.assertEqual([call.args for call in device.key.call_args_list], [("KEYCODE_DPAD_RIGHT",)] * 2)

    def test_tv_apps_badge_distinguishes_selected_scale(self):
        from PIL import Image, ImageDraw

        icon = ET.fromstring('<node bounds="[140,240][300,430]"/>')
        for radius, selected in [(60, False), (80, True)]:
            frame = Image.new("RGB", (1920, 1080))
            ImageDraw.Draw(frame).ellipse((220-radius, 330-radius, 220+radius, 330+radius),
                                          fill=(225, 47, 38))
            encoded = io.BytesIO()
            frame.save(encoded, format="PNG")
            self.assertEqual(release_ui.launcher_apps_highlight(encoded.getvalue(), icon), selected)

    def test_phone_more_retries_after_controls_hide(self):
        device = release_ui.Device("offline-test", "phone")
        device.tree = Mock(return_value=ET.fromstring("<hierarchy/>"))
        device.player_control = Mock(return_value=ET.fromstring('<node bounds="[80,180][120,220]"/>'))
        device.shell = Mock()
        device.wait_for = Mock(side_effect=[AssertionError("hidden"), object()])
        device.more()
        self.assertEqual(device.player_control.call_count, 2)
        self.assertEqual(device.shell.call_count, 2)

    def test_nested_guide_rows_are_collected_without_duplicates(self):
        root = ET.fromstring('''<hierarchy><node><node><node text="00:00"/>
            <node><node text="正在播 QA Entering Midnight"/></node></node>
            <node><node text="23:00"/><node text="QA Leaving Midnight"/></node>
            </node></hierarchy>''')
        self.assertEqual(programme_rows(root), {
            ("00:00", "正在播 QA Entering Midnight"), ("23:00", "QA Leaving Midnight")})

    def test_merged_guide_rows_use_text_or_content_description(self):
        root = ET.fromstring('<hierarchy><node text="00:00, Current"/><node content-desc="23:00 Next"/></hierarchy>')
        self.assertEqual(programme_rows(root), {("00:00", "Current"), ("23:00", "Next")})

    def test_tune_measurement_distinguishes_recovery_and_failure(self):
        events = parse_tunes("first_frame_ms=50 tune_ms=90 recovery_ms=30 first_line=false tune_id=5\n"
                             "tune_failed_id=6")
        self.assertEqual(events[5], {"first_frame_ms": 90, "recovery_ms": 30, "first_line": False, "status": "success"})
        self.assertEqual(events[6]["status"], "failed")
        self.assertEqual(summarize([90, 30, 50]), {"p50_ms": 50, "p95_ms": 90, "max_ms": 90})

    def test_flat_guide_matches_adjacent_cells_on_the_same_row(self):
        root = ET.fromstring("""<hierarchy><node>
            <node text="Today"/><node text="00:00" bounds="[0,20][40,40]"/>
            <node text="Entering" bounds="[50,18][190,38]"/>
            <node text="23:00" bounds="[0,50][40,70]"/>
            <node text="Leaving" bounds="[50,50][190,70]"/>
            </node></hierarchy>""")
        self.assertEqual(programme_rows(root), {("00:00", "Entering"), ("23:00", "Leaving")})

    def test_fixture_date_comes_from_device(self):
        device = Mock(device_type="tv")
        device.shell.return_value = "2026-11-12T-0330\n"
        self.assertEqual(guide_address(device),
                         "http://qa-iptv.test/midnight.xml?offset=-210&device=tv&date=2026-11-12")

    def test_existing_text_is_deleted_before_input(self):
        device = Mock()
        device.tree.return_value = ET.fromstring('''<hierarchy><node focused="true"
            class="android.widget.EditText" text="127.0.0.1"/></hierarchy>''')
        type_in_field(device, "10.0.0.1")
        self.assertEqual(device.key.call_args_list[0].args, ("KEYCODE_MOVE_END",))
        self.assertEqual(device.key.call_args_list[1].args, ("KEYCODE_DEL",) * 9)
        device.input.assert_called_once_with("text", "'10.0.0.1'")
        device.wait_for.assert_called_once_with("10.0.0.1")

    def test_waits_for_delayed_control(self):
        device = release_ui.Device("offline-test", "phone")
        device.tree = Mock(side_effect=[ET.fromstring("<hierarchy/>"),
                                       ET.fromstring('<hierarchy><node text="保存"/></hierarchy>')])
        self.assertEqual(device.wait_for("保存").get("text"), "保存")

    def test_installer_does_not_accept_same_code_with_wrong_name(self):
        device = Mock(device_type="phone")
        device.shell.side_effect = ["versionCode=31 minSdk=26\nversionName=1.3.5\n",
                                   "versionCode=31 minSdk=26\nversionName=1.3.6\n"]
        device.tree.return_value = ET.fromstring('<hierarchy><node text="安装" enabled="true"/></hierarchy>')
        with patch("local_upgrade.time.sleep"):
            finish_installation(device, {"versionCode": 31, "versionName": "1.3.6"})
        device.tap.assert_called_once()
        self.assertEqual(device.shell.call_count, 2)

    def test_installer_reports_corrupt_package(self):
        device = Mock(device_type="phone")
        device.shell.return_value = "versionCode=30 minSdk=26\nversionName=1.3.5\n"
        device.tree.return_value = ET.fromstring('<hierarchy><node text="安装包已损坏"/></hierarchy>')
        with self.assertRaisesRegex(AssertionError, "安装包已损坏"):
            finish_installation(device, {"versionCode": 31, "versionName": "1.3.6"})
        device.tap.assert_not_called()

    def test_paired_candidate_uses_recorded_metadata(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / ".iptv-qa-run").touch()
            (root / "local-upgrade").mkdir()
            apk = root / "test.apk"
            apk.write_bytes(b"local-test-only")
            entry = {"versionCode": 30, "versionName": "1.3.5"}
            state = {"identity": {"phone": "test-phone", "to": entry,
                                  "candidateSha256": hashlib.sha256(apk.read_bytes()).hexdigest()},
                     "devices": {"phone": {"passed": True}, "tv": {"passed": True}}}
            (root / "local-upgrade/paired-run.json").write_text(json.dumps(state), encoding="utf-8")
            with patch.object(release_ui, "QA_OUTPUT", root), \
                    patch.dict("os.environ", {"IPTV_QA_APK": str(apk)}), \
                    patch.object(release_ui, "release_artifact", side_effect=AssertionError("stale build metadata")), \
                    patch.object(release_ui, "verify_installed") as verify:
                device = release_ui.Device("test-phone", "phone")
                release_ui.verify_release_apk(device)
                verify.assert_called_once_with(device, apk, entry)


class FixtureRegressionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = FixtureServer(("127.0.0.1", 0), Path("unused-test-media.mp4"))
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)

    def test_failed_lines_fixture_supplies_sixteen_distinct_failures(self):
        from urllib.error import HTTPError

        with urlopen(self.base + "/many-failed-lines.m3u", timeout=5) as response:
            lines = response.read().decode().splitlines()
        self.assertEqual(lines[1::2], ["#EXTINF:-1,QA Failed Lines"] * 16)
        self.assertEqual(lines[2::2], [f"/fail-line-{index}.mp4" for index in range(16)])
        with self.assertRaises(HTTPError) as failure:
            urlopen(self.base + lines[-1], timeout=5)
        self.assertEqual(failure.exception.code, 404)
        failure.exception.close()

    def test_tune_fixture_has_direct_and_backup_paths(self):
        with urlopen(self.base + "/tune.m3u", timeout=5) as response:
            body = response.read().decode()
        self.assertEqual(body.splitlines(), [
            "#EXTM3U", "#EXTINF:-1,QA Tune Direct", "/media.mp4?tune=direct",
            "#EXTINF:-1,QA Tune Recovery", "/fail-tune.mp4",
            "#EXTINF:-1,QA Tune Recovery", "/media.mp4?tune=backup",
        ])

    def test_hls_stall_fixture_is_live(self):
        with urlopen(self.base + "/stalling.m3u8", timeout=5) as response:
            body = response.read().decode()
        self.assertNotIn("#EXT-X-ENDLIST", body)
        self.assertIn("/stall-start.ts", body)
        self.assertIn("/stall-tail.ts", body)

    def test_midnight_fixture_respects_device_date_and_offset(self):
        with urlopen(self.base + "/midnight.xml?date=2026-11-12&offset=-210", timeout=5) as response:
            root = ET.fromstring(response.read())
        rows = {node.findtext("title"): node.attrib for node in root.findall("programme")}
        self.assertEqual(rows["QA Entering Midnight"]["start"], "20261111230000 -0330")
        self.assertEqual(rows["QA Entering Midnight"]["stop"], "20261112010000 -0330")
        self.assertEqual(rows["QA Leaving Midnight"]["stop"], "20261113010000 -0330")


if __name__ == "__main__":
    unittest.main()
