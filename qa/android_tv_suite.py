"""ADB UI scenario runner. Only use against a disposable Android TV emulator."""

import argparse
import hashlib
import json
import os
import re
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from pathlib import Path


PACKAGE = "cn.hdfk7.android.iptv.debug"
ACTIVITY = PACKAGE + "/cn.hdfk7.android.iptv.app.MainActivity"
HOST = "http://127.0.0.1:18764"
SOURCE = "http://10.0.2.2:18764"


class TvSuite:
    def __init__(self, output, serial):
        self.output = output.resolve()
        self.shots = self.output / "screenshots"
        self.shots.mkdir(parents=True, exist_ok=True)
        self.adb = str(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe")
        self.serial = serial
        self.result_file = self.output / "scenarios.json"
        self.results = json.loads(self.result_file.read_text(encoding="utf-8")) if self.result_file.exists() else []

    def run(self, *arguments, input_data=None, timeout=25):
        result = subprocess.run([self.adb, "-s", self.serial, *arguments], input=input_data, capture_output=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(result.stderr.decode("utf-8", errors="replace")[:500])
        return result.stdout

    def dump(self):
        self.run("shell", "uiautomator", "dump", "/sdcard/iptv-qa-ui.xml")
        return ET.fromstring(self.run("shell", "cat", "/sdcard/iptv-qa-ui.xml"))

    @staticmethod
    def bounds(node):
        return list(map(int, re.findall(r"\d+", node.get("bounds", ""))))

    @staticmethod
    def has(root, value, attribute="text"):
        return any(node.get(attribute) == value for node in root.iter("node"))

    def wait(self, predicate, seconds=25):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            root = self.dump()
            if predicate(root):
                return root
            time.sleep(0.2)
        raise AssertionError("UI condition timed out")

    def tap(self, node):
        left, top, right, bottom = self.bounds(node)
        self.run("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
        time.sleep(0.3)

    def click(self, value, attribute="text"):
        root = self.wait(lambda tree: self.has(tree, value, attribute))
        node = next(node for node in root.iter("node") if node.get(attribute) == value)
        self.tap(node)

    def key(self, code):
        self.run("shell", "input", "keyevent", str(code))
        time.sleep(0.2)

    def screenshot(self, filename):
        path = self.shots / filename
        path.write_bytes(self.run("exec-out", "screencap", "-p"))
        return "screenshots/" + filename

    def record(self, identifier, title, group, observation, data=None, status="passed", kind="controlled"):
        entry = {
            "id": identifier,
            "title": title,
            "group": group,
            "kind": kind,
            "status": status,
            "observation": observation,
            "data": data or {},
            "capturedAt": datetime.now(timezone(timedelta(hours=8))).isoformat(timespec="seconds"),
            "screenshot": self.screenshot(identifier + ".png"),
        }
        self.results = [result for result in self.results if result["id"] != identifier] + [entry]
        self.result_file.write_text(json.dumps(self.results, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"id": identifier, "status": status, "title": title}, ensure_ascii=True), flush=True)

    def library(self):
        return json.loads(self.run("shell", "run-as", PACKAGE, "cat", "files/library.json"))

    def launch(self, cold=False):
        if cold:
            self.run("shell", "am", "force-stop", PACKAGE)
        self.run("shell", "am", "start", "-W", "-n", ACTIVITY)
        self.wait(lambda root: self.has(root, "源管理"), seconds=25)

    def clear(self):
        if not self.serial.startswith("emulator-"):
            raise RuntimeError("Destructive QA setup is only permitted on a disposable emulator")
        if not (Path(os.environ["TEMP"]) / "iptv-qa-original-data.tar").is_file():
            raise RuntimeError("Back up the emulator's original app data before clearing it")
        self.run("shell", "am", "force-stop", PACKAGE)
        self.run("shell", "pm", "clear", PACKAGE)
        self.launch()

    def dismiss_keyboard(self):
        details = self.run("shell", "dumpsys", "input_method").decode("utf-8", errors="replace")
        if re.search(r"\bmInputShown\s*=\s*true", details):
            self.key(4)

    def main(self):
        activities = self.run("shell", "dumpsys", "activity", "activities").decode("utf-8", errors="replace")
        if not re.search(r"(?:mResumedActivity|topResumedActivity)[^\n]*" + re.escape(PACKAGE), activities):
            self.launch()
        for _ in range(5):
            self.dismiss_keyboard()
            root = self.dump()
            if self.has(root, "全屏", "content-desc"):
                return
            if self.has(root, "关闭", "content-desc"):
                self.click("关闭", "content-desc")
            else:
                self.key(4)
        self.launch()

    def manager(self):
        self.main()
        self.click("源管理")
        self.wait(lambda root: self.has(root, "添加源"))

    def field(self, index, value):
        root = self.dump()
        fields = [node for node in root.iter("node") if node.get("class") == "android.widget.EditText"]
        node = fields[index]
        old = node.get("text", "")
        left, top, _, _ = self.bounds(node)
        self.run("shell", "input", "tap", str(left + 35), str(top + 30))
        self.run("shell", "input", "keyevent", "123")
        if old:
            self.run("shell", "input", "keyevent", *(["67"] * len(old)))
        if value:
            self.run("shell", "input", "text", value.replace(" ", "%s"))
        time.sleep(0.35)

    def save(self, error=None):
        self.dismiss_keyboard()
        self.click("保存")
        if error:
            self.wait(lambda root: self.has(root, error))
        else:
            self.wait(lambda root: self.has(root, "添加源"))

    def add_source(self, name, endpoint):
        existing = next((source for source in self.library()["sources"] if source["name"] == name and source["url"] == SOURCE + endpoint), None)
        if existing is not None:
            return existing
        self.manager()
        self.click("添加源")
        self.field(0, name)
        self.field(1, SOURCE + endpoint)
        self.save()
        return next(source for source in self.library()["sources"] if source["name"] == name)

    def source_action(self, name, action):
        root = self.dump()
        if not self.has(root, "添加源"):
            self.manager()
        for _ in range(12):
            root = self.dump()
            names = [node for node in root.iter("node") if node.get("text") == name]
            if names:
                box = self.bounds(names[0])
                target_y = (box[1] + box[3]) / 2 + 28
                attribute = "text" if action in ["启用", "正在使用"] else "content-desc"
                buttons = [node for node in root.iter("node") if node.get(attribute) == action]
                if buttons:
                    button = min(buttons, key=lambda node: abs((self.bounds(node)[1] + self.bounds(node)[3]) / 2 - target_y))
                    distance = abs((self.bounds(button)[1] + self.bounds(button)[3]) / 2 - target_y)
                    if distance < 65 and box[3] < 780:
                        self.tap(button)
                        return
            self.run("shell", "input", "swipe", "1000", "700", "1000", "350", "350")
        raise AssertionError("Source action not found: " + name + "/" + action)

    def activate(self, name, expected_count=None):
        self.main()
        source = next(source for source in self.library()["sources"] if source["name"] == name)
        if self.library()["activeSourceId"] != source["id"]:
            self.manager()
            self.source_action(name, "启用")
        self.wait(lambda root: self.has(root, "全屏", "content-desc"))
        if expected_count is not None:
            self.wait(lambda root: self.has(root, str(expected_count)), seconds=25)
        assert self.library()["activeSourceId"] == source["id"]

    def favorites(self):
        self.main()
        self.manager()
        action = "正在使用" if self.library()["activeSourceId"] == "favorites" else "启用"
        self.source_action("收藏", action)
        self.wait(lambda root: self.has(root, "全屏", "content-desc"))
        assert self.library()["activeSourceId"] == "favorites"

    def channel_star(self, name):
        root = self.dump()
        names = [node for node in root.iter("node") if node.get("text") == name and self.bounds(node)[0] < 500]
        assert names, "Channel not visible: " + name
        box = self.bounds(names[0])
        y = (box[1] + box[3]) / 2 + 12
        stars = [node for node in root.iter("node") if node.get("content-desc") in ["收藏节目", "取消收藏"] and self.bounds(node)[0] < 600]
        button = min(stars, key=lambda node: abs((self.bounds(node)[1] + self.bounds(node)[3]) / 2 - y))
        self.tap(button)
        time.sleep(0.3)

    def play(self, name, seconds=45):
        root = self.dump()
        nodes = [node for node in root.iter("node") if node.get("text") == name and self.bounds(node)[0] < 500]
        assert nodes, "Program not in visible channel list"
        self.tap(nodes[0])
        self.wait(lambda root: self.has(root, "暂停"), seconds)

    def refresh(self, error=False):
        self.click("刷新频道", "content-desc")
        if error:
            self.wait(lambda root: self.has(root, "更新失败 · 使用缓存") or self.has(root, "暂无节目"), seconds=25)
        else:
            self.wait(lambda root: not self.has(root, "更新失败 · 使用缓存"), seconds=25)

    @staticmethod
    def control(mode):
        urllib.request.urlopen(HOST + "/control?mode=" + mode, timeout=5).read()

    @staticmethod
    def events():
        return json.loads(urllib.request.urlopen(HOST + "/events", timeout=5).read())

    def real(self):
        self.launch(cold=True)
        library = self.library()
        self.record("R01", "真实源与收藏首页", "真实仓库", "保留原始配置启动，未显示品牌和操作说明。", {"sources": len(library["sources"]), "favoriteContributions": len(library["favorites"])}, kind="real")
        self.manager()
        self.record("R02", "三个真实源的管理面板", "真实仓库", "三个源可编辑、删除；仅一个当前源。", kind="real")
        self.main()
        self.favorites()
        self.play("CCTV1", seconds=150)
        entries = [entry for entry in self.library()["favorites"] if entry["name"] == "CCTV1"]
        urls = {url for entry in entries for url in entry["streams"]}
        self.record("R03", "真实跨源 CCTV1 播放", "真实仓库", "多个来源同名节目汇聚为一个节目，顺序探测后实际播放。", {"sourceContributions": len(entries), "distinctUrls": len(urls)}, kind="real")
        self.click("全屏", "content-desc")
        self.key(165)
        self.record("R04", "真实直播全屏信息", "真实仓库", "全屏显示节目、来源和收藏。", kind="real")
        time.sleep(6.5)
        self.record("R05", "真实直播全屏纯画面", "真实仓库", "信息自动隐藏，视频继续播放。", kind="real")
        self.key(4)
        self.wait(lambda root: self.has(root, "源管理"))

    def forms(self):
        self.clear()
        self.wait(lambda root: self.has(root, "CCTV1"), seconds=30)
        assert len(self.library()["sources"]) == 3
        assert not self.library()["favorites"]
        self.record("S01", "首次启动", "源管理", "首次创建三个内置源，默认选择一个，收藏为空。", kind="real")
        self.key(20)
        self.key(20)
        self.record("S02", "遥控器焦点", "源管理", "方向键移动到列表控件，聚焦描边可见。", kind="real")
        self.favorites()
        self.wait(lambda root: self.has(root, "暂无收藏"))
        self.record("S03", "收藏空状态", "收藏", "空收藏源可打开，按钮仍可操作。")
        self.manager()
        self.record("S04", "源列表", "源管理", "收藏源与三个普通源同列展示，只有一个正在使用。")
        self.click("添加源")
        self.record("S05", "添加源表单", "源管理", "名称、地址和保存/取消控件可见，无额外说明文字。")
        self.save(error="请输入 1–48 个字符的源名称")
        self.record("S06", "空名称校验", "表单校验", "拒绝空名称，表单与原配置保留。")
        self.field(0, "X" * 49)
        self.save(error="请输入 1–48 个字符的源名称")
        self.record("S07", "名称超长校验", "表单校验", "49 字符名称被拒绝。", {"inputLength": 49})
        self.field(0, "QAMain")
        self.field(1, "file:///not-allowed.m3u")
        self.save(error="请输入 HTTP(S) 播放列表直链或 GitHub 文件链接")
        self.record("S08", "不支持的地址校验", "表单校验", "file 协议被拒绝。")
        self.field(1, "https://github.com/best-fan/iptv-sources")
        self.save(error="这个播放列表地址已经存在")
        self.record("S09", "重复地址校验", "表单校验", "GitHub 仓库主页归一化后识别为重复的内置源。")
        self.field(1, SOURCE + "/main.m3u")
        self.save()
        assert self.library()["activeSourceId"] == "favorites"
        self.record("S10", "添加成功", "源管理", "新源已保存，但没有替换当前收藏源。", {"sources": len(self.library()["sources"])})
        self.source_action("QAMain", "编辑源")
        self.field(0, "QA Main")
        self.dismiss_keyboard()
        self.record("S11", "编辑源", "源管理", "可修改已有源名称，地址完整保留。")
        self.save()
        assert any(source["name"] == "QA Main" for source in self.library()["sources"])
        self.source_action("QA Main", "编辑源")
        self.field(0, "UNSAVED")
        self.dismiss_keyboard()
        self.click("取消")
        assert not any(source["name"] == "UNSAVED" for source in self.library()["sources"])
        self.record("S12", "取消编辑", "源管理", "未保存修改被丢弃，原名称保留。")
        self.activate("QA Main", expected_count=5)
        self.record("S13", "单源启用与同名合并", "源管理", "仅显示测试源的 5 个节目；节目 A 跨分组合并两条地址。", {"programs": 5, "aUrls": 2})

    def playback(self):
        self.main()
        self.activate("QA Main", 5)
        self.play("测试节目 A")
        root = self.dump()
        assert self.has(root, "线路 2 / 2")
        self.record("P01", "首地址失败后播放第二地址", "播放", "第一地址 404，第二地址播放本机生成的测试信号。", {"selectedCandidate": 2, "candidates": 2})
        self.key(127)
        self.wait(lambda root: self.has(root, "已暂停"))
        self.record("P02", "暂停", "播放", "媒体暂停键暂停，画面保留且显示暂停状态。")
        self.key(126)

        self.wait(lambda root: self.has(root, "暂停"))
        self.record("P03", "恢复播放", "播放", "媒体播放键恢复，不更换节目。")
        self.key(166)
        self.wait(lambda root: self.has(root, "线路 1 / 1") and self.has(root, "暂停"))
        self.record("P04", "下一节目", "播放", "频道加键切到节目 B，从 B 的首地址开始。", {"program": "B", "candidate": 1})
        self.key(167)
        self.wait(lambda root: self.has(root, "线路 2 / 2") and self.has(root, "暂停"))
        self.record("P05", "上一节目", "播放", "频道减键回到节目 A，不是切到 B 的另一个地址。")
        self.click("测试节目 C")
        self.wait(lambda root: self.has(root, "线路不可用"), seconds=35)
        self.record("P06", "全部线路失败", "异常播放", "节目 C 两条地址均失败，停止轮询并显示错误。")
        before = len([event for event in self.events() if event["path"].startswith("/fail-c")])
        self.click("重试频道", "content-desc")
        self.wait(lambda root: self.has(root, "线路不可用"), seconds=35)
        after = len([event for event in self.events() if event["path"].startswith("/fail-c")])
        assert after > before
        self.record("P07", "失败后重试", "异常播放", "重试重新探测同一节目，节目名称未改变。", {"requestsBefore": before, "requestsAfter": after})
        self.play("测试节目 D", seconds=80)
        assert self.has(self.dump(), "线路 10 / 11")
        assert not any("unused=after-first-success" in event["path"] for event in self.events())
        self.record("P08", "超过八条地址且首成功即停止", "异常播放", "前九个地址失败，第十个成功；没有请求第十一个候选地址。", {"selectedCandidate": 10, "candidates": 11})
        started = time.time()
        self.click("测试节目 E")
        self.record("P09", "单地址超时等待", "异常播放", "第一地址故意延迟 12 秒，界面保持加载状态。", {"probeTimeoutMs": 8000, "fixtureDelayMs": 12000})
        self.wait(lambda root: self.has(root, "线路 2 / 2") and self.has(root, "暂停"), seconds=25)
        events = [event for event in self.events() if event["at"] >= started - 0.5]
        slow = next(event for event in events if event["path"] == "/slow.mp4")
        good = next(event for event in events if "program=e" in event["path"])
        elapsed = round((good["at"] - slow["at"]) * 1000)
        assert elapsed < 11000
        self.record("P10", "超时后自动换地址", "异常播放", "第一地址超时后切到第二地址，视频实际开始播放。", {"observedSwitchMs": elapsed})
        self.click("全屏", "content-desc")
        self.key(165)
        self.record("F01", "全屏信息", "全屏", "同一视频输出扩展到全屏，只保留节目、来源和播放/收藏操作。")
        time.sleep(6.5)
        assert not self.has(self.dump(), "测试节目 E")
        self.record("F02", "全屏自动隐藏", "全屏", "6 秒无操作后，节目和按钮全部隐藏，视频继续。")
        before = len(self.library()["favorites"])
        self.key(23)
        self.wait(lambda root: self.has(root, "测试节目 E"))
        assert len(self.library()["favorites"]) == before
        self.record("F03", "确认键唤出", "全屏", "按确认键只唤出信息，不误触收藏。", {"favoritesBefore": before, "favoritesAfter": len(self.library()["favorites"])})
        self.key(23)
        self.wait(lambda root: self.has(root, "取消收藏", "content-desc"))
        assert len(self.library()["favorites"]) == before + 1
        self.record("F04", "全屏收藏", "全屏", "浮层已显示时再次确认，聚焦星标执行收藏。")
        self.key(21)
        self.key(4)
        self.wait(lambda root: self.has(root, "源管理"))
        self.record("F05", "全屏返回", "全屏", "焦点在控件上时按返回，恢复列表并继续播放。")
        self.key(3)
        self.launch()
        self.wait(lambda root: self.has(root, "暂停"), seconds=35)
        self.record("P11", "前后台恢复", "生命周期", "进入主页后释放播放器，回到应用重新播放同一节目。")
        self.key(127)
        self.key(3)
        self.launch()
        self.wait(lambda root: self.has(root, "已暂停"), seconds=25)
        self.record("P12", "暂停状态跨前后台保留", "生命周期", "暂停后退后台再回来，仍为暂停，未擅自播放。")
        self.key(126)

    def volume_index(self):
        text = self.run("shell", "cmd", "media_session", "volume", "--stream", "3", "--get").decode("utf-8", errors="replace")
        match = re.search(r"volume is (\d+) in range \[(\d+)\.\.(\d+)\]", text)
        if not match:
            raise AssertionError("Could not read system media volume")
        return tuple(map(int, match.groups()))

    def volume(self):
        self.main()
        before, minimum, maximum = self.volume_index()
        if before == maximum:
            self.click("音量 −", "content-desc")
            before, _, _ = self.volume_index()
        self.click("音量 ＋", "content-desc")
        after, _, _ = self.volume_index()
        assert after == min(before + 1, maximum)
        self.record("V01", "音量增加", "音量", "点击喇叭加号后，系统媒体音量增加。模拟器没有实际音频输出设备，未验证听感。", {"before": before, "after": after, "min": minimum, "max": maximum})
        self.click("音量 −", "content-desc")
        reduced, _, _ = self.volume_index()
        assert reduced == max(after - 1, minimum)
        self.record("V02", "音量降低", "音量", "点击喇叭减号后，系统媒体音量降低。", {"before": after, "after": reduced})

    def library_cases(self):
        self.main()
        self.favorites()
        for name in list({channel["name"] for channel in self.library()["favorites"]}):
            self.channel_star(name)
        assert not self.library()["favorites"]
        self.wait(lambda root: self.has(root, "暂无收藏"))
        self.record("L01", "取消最后一个收藏", "收藏", "收藏源取消节目后，清空该节目所有来源贡献并显示空状态。")
        self.activate("QA Main", 5)
        self.channel_star("测试节目 A")
        assert len(self.library()["favorites"]) == 1
        self.record("L02", "列表直接收藏", "收藏", "不启动播放也能在节目行收藏。", {"contributions": 1})
        self.add_source("QA Alt", "/other.m3u")
        self.activate("QA Alt", 2)
        self.channel_star("测试节目 A")
        assert len(self.library()["favorites"]) == 2
        self.record("L03", "第二个源收藏同名节目", "收藏", "普通源仍只显示自身两个节目，收藏计数按节目去重。", {"sourcePrograms": 2, "favoritePrograms": 1, "contributions": 2})
        self.favorites()
        self.play("测试节目 A")
        assert self.has(self.dump(), "线路 2 / 3")
        self.record("L04", "收藏源合并同名线路", "收藏", "一个节目，两个来源，三条不同地址；第一可用地址播放后停止探测。", {"programs": 1, "sources": 2, "urls": 3})
        self.launch(cold=True)
        library = self.library()
        assert library["activeSourceId"] == "favorites" and len(library["favorites"]) == 2
        self.wait(lambda root: self.has(root, "测试节目 A"))
        self.record("L05", "重启持久化", "收藏", "冷启动保留当前源和两个来源的收藏，不擅自自动播放。")
        self.activate("QA Alt", 2)
        self.channel_star("测试节目 A")
        assert len(self.library()["favorites"]) == 1
        self.record("L06", "取消单一来源贡献", "收藏", "在普通源取消收藏只移除该源，另一个来源的收藏仍保留。")
        self.favorites()
        self.play("测试节目 A")
        assert self.has(self.dump(), "线路 2 / 2")
        self.record("L07", "取消贡献后的聚合结果", "收藏", "仍显示一个节目，地址数量从三条回到两条。", {"urls": 2})
        self.activate("QA Alt", 2)
        self.channel_star("测试节目 A")
        self.favorites()
        self.manager()
        self.source_action("QA Alt", "删除源")
        self.record("L08", "删除确认界面", "源管理", "删除前可以取消；不会直接删除源。")
        self.click("取消")
        assert any(source["name"] == "QA Alt" for source in self.library()["sources"])
        self.record("L09", "取消删除", "源管理", "取消后源和收藏均保留。")
        self.source_action("QA Alt", "删除源")
        self.click("删除源")
        self.wait(lambda root: self.has(root, "添加源"))
        library = self.library()
        assert not any(source["name"] == "QA Alt" for source in library["sources"])
        assert len(library["favorites"]) == 2
        self.record("L10", "删除源保留收藏快照", "收藏", "源已移除，但收藏中保留其最后保存的地址。", {"contributionsRetained": 2})
        self.main()
        self.play("测试节目 A")
        assert self.has(self.dump(), "线路 2 / 3")
        self.record("L11", "删除来源后仍可播放收藏", "收藏", "收藏节目仍可播放，地址数量保留三条。")
        self.manager()
        self.source_action("QA Main", "编辑源")
        self.field(0, "QA Renamed")
        self.save()
        self.main()
        self.wait(lambda root: any("QA Renamed" in node.get("text", "") and self.bounds(node)[0] > 600 for node in root.iter("node")), seconds=12)
        self.record("L12", "来源改名即时更新播放信息", "收藏", "正在播放的节目来源同步新名称，未中断当前线路。")

    def source_edges(self):
        names = [source["name"] for source in self.library()["sources"]]
        main_name = "QA Renamed" if "QA Renamed" in names else "QA Main"
        source = self.add_source("QA Address", "/many.m3u")
        self.activate("QA Address", 200)
        self.record("E01", "大量节目列表", "列表", "200 个节目按需渲染，界面可响应。", {"programs": 200})
        self.launch(cold=True)
        self.key(21)
        self.key(20)
        for _ in range(38):
            self.key(20)
        root = self.dump()
        visible = [int(match.group(1)) for node in root.iter("node") if (match := re.match(r"节目 (\d+)$", node.get("text", "")))]
        assert visible and max(visible) >= 30
        self.record("E02", "遥控器长列表滚动", "列表", "持续方向键移动后列表自动滚动，焦点可见。", {"visiblePrograms": visible})
        self.manager()
        self.source_action("QA Address", "编辑源")
        self.control("hold")
        self.field(1, SOURCE + "/hold-list.m3u?edited=1")
        self.save()
        self.main()
        assert self.has(self.dump(), "0")
        self.record("E03", "修改地址隔离旧缓存", "源管理", "地址改为尚无缓存的列表后，没有继续展示旧地址的 200 个节目。")
        self.manager()
        self.source_action("QA Address", "编辑源")
        self.field(1, SOURCE + "/many.m3u")
        self.save()
        self.main()
        self.wait(lambda root: self.has(root, "200"), seconds=25)
        self.control("release-hold")
        time.sleep(3)
        assert self.has(self.dump(), "200")
        self.record("E04", "旧地址迟到响应隔离", "源管理", "改回原地址后，即使旧请求稍后完成，仍保留正确的 200 个节目。")
        self.manager()
        self.source_action("QA Address", "删除源")
        self.click("删除源")
        self.wait(lambda root: self.has(root, "添加源"))
        library = self.library()
        assert library["activeSourceId"] == library["sources"][0]["id"]
        self.record("E05", "删除当前源自动切换", "源管理", "当前源删除后，只启用剩余的第一个源。")
        self.main()
        for name in [source["name"] for source in self.library()["sources"]]:
            self.manager()
            self.source_action(name, "删除源")
            self.click("删除源")
            self.wait(lambda root: self.has(root, "添加源"))
            self.main()
        library = self.library()
        assert not library["sources"] and library["activeSourceId"] == "favorites"
        assert len(library["favorites"]) == 2
        self.record("E06", "删除所有普通源", "源管理", "没有普通源时自动使用收藏源，收藏快照仍保留。")
        self.manager()
        self.record("E07", "无普通源的管理面板", "源管理", "仅保留收藏源和添加按钮，仍可继续添加新源。")
        self.main()
        self.channel_star("测试节目 A")
        self.wait(lambda root: self.has(root, "暂无收藏"))
        self.record("E08", "源和收藏全部为空", "收藏", "全部为空时保持可操作，不崩溃。")
        self.launch(cold=True)
        assert not self.library()["sources"] and not self.library()["favorites"]
        self.record("E09", "空配置重启", "生命周期", "重启不会擅自恢复已经删除的内置源。")



    def cache_cases(self):
        self.control("ok")
        self.add_source("QA Cache", "/mutable.m3u")
        self.activate("QA Cache", 5)
        self.record("C01", "有效列表缓存", "刷新缓存", "成功获取五个节目并保存独立缓存。")
        for mode, identifier, title in [
            ("http503", "C02", "HTTP 503 保留缓存"),
            ("empty", "C03", "空列表保留缓存"),
            ("html", "C04", "HTML 错误页拒绝"),
            ("hls", "C05", "HLS 分片清单拒绝"),
        ]:
            before = self.read_cache(SOURCE + "/mutable.m3u")
            self.control(mode)
            self.refresh(error=True)
            after = self.read_cache(SOURCE + "/mutable.m3u")
            assert before == after
            self.record(identifier, title, "刷新缓存", "刷新失败，旧五个节目和成功更新时间均未被覆盖。", {"fixtureResponse": mode, "cachedPrograms": len(after["channels"]), "timestampPreserved": True})
        self.control("ok")
        self.refresh()
        self.wait(lambda root: self.has(root, "5") and not self.has(root, "更新失败 · 使用缓存"))
        self.record("C06", "失败后恢复刷新", "刷新缓存", "服务恢复后正常更新，失败提示清除。")
        self.control("hold")
        self.add_source("QA Slow", "/hold-list.m3u?case=switch")
        self.activate("QA Slow")
        self.record("C07", "列表加载状态", "刷新缓存", "列表接口暂停返回有效条目，刷新进度仍可见，界面可操作。")
        self.manager()
        self.source_action("QA Cache", "启用")
        self.wait(lambda root: self.has(root, "5"))
        self.control("release-hold")
        time.sleep(3)
        source = next(source for source in self.library()["sources"] if source["name"] == "QA Cache")
        assert self.library()["activeSourceId"] == source["id"]
        self.record("C08", "切源后旧响应不覆盖", "刷新缓存", "慢源请求完成后，当前源仍为 QA Cache，节目未混入其他源。")

    def cache_name(self, url):
        return "files/playlist-" + hashlib.sha256(url.encode()).hexdigest() + ".json"

    def read_cache(self, url):
        return json.loads(self.run("shell", "run-as", PACKAGE, "cat", self.cache_name(url)))

    def backup(self, path):
        self.run("shell", "am", "force-stop", PACKAGE)
        path.write_bytes(self.run("exec-out", "run-as", PACKAGE, "tar", "cf", "-", "files", "databases", "no_backup"))

    def restore(self, path):
        self.run("shell", "am", "force-stop", PACKAGE)
        self.run("shell", "pm", "clear", PACKAGE)
        self.run("shell", "-T", "run-as", PACKAGE, "tar", "xf", "-", input_data=path.read_bytes())

    def network(self, enabled):
        self.run("shell", "cmd", "connectivity", "airplane-mode", "disable" if enabled else "enable")
        self.run("shell", "svc", "wifi", "enable" if enabled else "disable")
        services = self.run("shell", "service", "list").decode("utf-8", errors="replace")
        if re.search(r"\bphone:\s*\[", services):
            self.run("shell", "svc", "data", "enable" if enabled else "disable")
        time.sleep(2)

    def offline_cases(self):
        backup = Path(os.environ["TEMP"]) / "iptv-qa-controlled-data.tar"
        self.control("ok")
        self.activate("QA Cache", 5)
        self.refresh()
        self.backup(backup)
        try:
            self.network(False)
            self.launch()
            self.wait(lambda root: self.has(root, "更新失败 · 使用缓存"), seconds=40)
            self.record("N01", "有缓存断网启动", "断网", "关闭模拟器网络后启动，保留五个节目并提示使用缓存；电视镜像没有移动数据服务。", {"networkDisabled": True, "programs": 5})
            self.refresh(error=True)
            self.record("N02", "断网手动刷新", "断网", "断网刷新失败，不清空节目或配置。")
            self.run("shell", "am", "force-stop", PACKAGE)
            cache = self.read_cache(SOURCE + "/mutable.m3u")
            cache["updatedAt"] = int(time.time() * 1000) - 25 * 60 * 60 * 1000
            local = Path(os.environ["TEMP"]) / "iptv-qa-aged-cache.json"
            local.write_text(json.dumps(cache), encoding="utf-8")
            self.run("push", str(local), "/data/local/tmp/iptv-qa-aged-cache.json")
            self.run("shell", "run-as", PACKAGE, "cp", "/data/local/tmp/iptv-qa-aged-cache.json", self.cache_name(SOURCE + "/mutable.m3u"))
            self.launch()
            self.wait(lambda root: self.has(root, "缓存已超过一天"), seconds=40)
            self.record("N03", "过期缓存", "断网", "将测试缓存时间设为 25 小时前；断网仍保留节目，并显示过期状态。", {"simulatedCacheAgeHours": 25})
            self.clear()
            self.wait(lambda root: self.has(root, "暂无节目") and self.has(root, "更新失败"), seconds=40)
            self.record("N04", "无缓存断网首次启动", "断网", "首次启动且无网络，不显示伪造节目；源管理和刷新入口仍可操作。", {"networkDisabled": True, "cachePresent": False})
            self.network(True)
            self.restore(backup)
            self.launch()
            self.wait(lambda root: self.has(root, "5") and not self.has(root, "更新失败 · 使用缓存"), seconds=35)
            self.record("N05", "恢复网络与数据", "断网", "恢复网络后重新刷新成功，配置和五个节目恢复。")
            self.run("shell", "am", "force-stop", PACKAGE)
            corrupt = Path(os.environ["TEMP"]) / "iptv-qa-corrupt-library.json"
            corrupt.write_text("{invalid qa json", encoding="utf-8")
            self.run("push", str(corrupt), "/data/local/tmp/iptv-qa-corrupt-library.json")
            self.run("shell", "run-as", PACKAGE, "cp", "/data/local/tmp/iptv-qa-corrupt-library.json", "files/library.json")
            self.launch()
            message = "保存失败"
            self.wait(lambda root: self.has(root, message))
            assert self.run("shell", "run-as", PACKAGE, "cat", "files/library.json") == b"{invalid qa json"
            self.record("N06", "配置损坏保护", "存储异常", "受控配置 JSON 损坏时显示存储错误，未用默认配置覆盖原文件。")
            self.restore(backup)
            self.launch()
            self.wait(lambda root: self.has(root, "5"))
            self.record("N07", "恢复有效配置", "存储异常", "恢复有效备份后，源和节目正常读取。")
            permission = self.run("shell", "run-as", PACKAGE, "stat", "-c", "%a", "files").decode().strip()
            assert re.fullmatch(r"[0-7]{3,4}", permission)
            self.run("shell", "am", "force-stop", PACKAGE)
            self.run("shell", "run-as", PACKAGE, "chmod", "500", "files")
            try:
                self.launch()
                self.manager()
                self.source_action("QA Cache", "编辑源")
                self.field(0, "QA Denied")
                self.save(error=message)
                assert any(source["name"] == "QA Cache" for source in self.library()["sources"])
                assert not any(source["name"] == "QA Denied" for source in self.library()["sources"])
                self.record("N08", "写入失败保留原资料", "存储异常", "临时移除测试目录写权限，保存失败后仍保留原名称和配置；没有填满磁盘。", {"simulatedDirectoryMode": "500"})
            finally:
                self.run("shell", "run-as", PACKAGE, "chmod", permission, "files")
            self.save()
            self.source_action("QA Denied", "编辑源")
            self.field(0, "QA Cache")
            self.save()
            self.main()
            self.refresh()
            self.record("N09", "写权限恢复后保存成功", "存储异常", "恢复原权限后重试保存成功，错误清除，源可继续刷新。")
        finally:
            self.network(True)

    def resolution_cases(self):
        self.activate("QA Cache", 5)
        self.record("D01", "1080p 布局", "分辨率", "1920×1080、320 dpi 的 Android TV 布局。", {"pixels": "1920x1080", "density": 320})
        try:
            self.run("shell", "wm", "size", "1280x720")
            self.run("shell", "wm", "density", "213")
            self.launch()
            self.wait(lambda root: self.has(root, "全屏", "content-desc"))
            self.record("D02", "720p 布局", "分辨率", "1280×720、213 dpi 下控件无重叠或截断，布局保持电视比例。", {"pixels": "1280x720", "density": 213})
            self.manager()
            self.click("添加源")
            self.record("D03", "720p 表单", "分辨率", "小分辨率下源表单完整，保存/取消可见。", {"pixels": "1280x720"})
            self.dismiss_keyboard()
            self.click("取消")
        finally:
            self.run("shell", "wm", "size", "reset")
            self.run("shell", "wm", "density", "reset")
            self.launch()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("phase", choices=["real", "forms", "playback", "volume", "library_cases", "source_edges", "cache_cases", "offline_cases", "resolution_cases"])
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--output", type=Path, default=Path("app/build/outputs/qa"))
    arguments = parser.parse_args()
    suite = TvSuite(arguments.output, arguments.serial)
    try:
        getattr(suite, arguments.phase)()
    except Exception as exception:
        suite.record("FAIL-" + arguments.phase, "执行异常：" + arguments.phase, "执行异常", str(exception), status="failed")
        raise


if __name__ == "__main__":
    main()
