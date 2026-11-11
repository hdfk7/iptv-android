"""Isolated local TV QA playlists and a generated, rights-free test signal."""

import argparse
from datetime import datetime, timedelta, timezone
import json
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse


class FixtureServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, media):
        super().__init__(address, FixtureHandler)
        self.media = media
        self.mode = "ok"
        self.release_hold = threading.Event()
        self.events = []
        self.event_lock = threading.Lock()


class FixtureHandler(BaseHTTPRequestHandler):
    def log_message(self, format_string, *arguments):
        pass

    def send_body(self, body, mime="text/plain; charset=utf-8", status=200):
        data = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        query = parse_qs(parsed.query)
        if path == "/control":
            self.server.mode = query.get("mode", ["ok"])[0]
            if self.server.mode == "hold":
                self.server.release_hold.clear()
            elif self.server.mode == "release-hold":
                self.server.release_hold.set()
            self.send_body(json.dumps({"mode": self.server.mode}), "application/json")
            return
        if path == "/events":
            with self.server.event_lock:
                events = list(self.server.events)
            self.send_body(json.dumps(events), "application/json")
            return
        if path == "/health":
            self.send_body("ready")
            return
        with self.server.event_lock:
            self.server.events.append({"path": self.path, "at": time.time()})
        try:
            self.serve_fixture(path)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass

    def serve_fixture(self, path):
        held = path == "/hold-list.m3u"
        if held:
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.apple.mpegurl")
            self.end_headers()
            self.wfile.write(b"#EXTM3U\n")
            self.wfile.flush()
            while not self.server.release_hold.wait(2):
                self.wfile.write(b"# QA keepalive\n")
                self.wfile.flush()
        if path == "/guide.xml":
            self.send_response(302)
            self.send_header("Location", "/guide-data.xml")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if path == "/release.m3u":
            self.send_body("""#EXTM3U
#EXTINF:-1 tvg-id="101",QA Release
/fail-release.mp4
#EXTINF:-1 tvg-id="101",QA Release
/media.mp4?release=1
""", "application/vnd.apple.mpegurl")
            return
        if path == "/release-guide.xml" and self.server.mode == "release-offline":
            self.send_body("deliberate guide failure", status=503)
            return
        if path in ("/guide-data.xml", "/release-guide.xml"):
            now = datetime.now(timezone.utc)

            def stamp(minutes):
                return (now + timedelta(minutes=minutes)).strftime("%Y%m%d%H%M%S %z")

            self.send_body(f"""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE tv SYSTEM "xmltv.dtd">
<tv>
    <channel id="101"><display-name>QA Channel A</display-name></channel>
    <programme channel="101" start="{stamp(-30)}" stop="{stamp(-10)}"><title>QA Earlier</title></programme>
    <programme channel="101" start="{stamp(-10)}" stop="{stamp(10)}"><title>QA Now &amp; Live</title></programme>
    <programme channel="101" start="{stamp(10)}" stop="{stamp(30)}"><title>QA Next</title></programme>
</tv>""", "application/xml")
            return
        if path == "/debug-smoke.m3u":
            if self.server.mode == "http503":
                self.send_body("deliberate update failure", status=503)
                return
            self.send_body("""#EXTM3U x-tvg-url="/guide.xml"
#EXTINF:-1 tvg-id="qa-a",QA Channel A
/fail-a.mp4
#EXTINF:-1 tvg-id="qa-a",QA Channel A
/media.mp4?smoke=a
#EXTINF:-1,QA Channel B
/media.mp4?smoke=b
#EXTINF:-1,QA Channel C
/stalling.m3u8
#EXTINF:-1,QA Channel C
/media.mp4?smoke=c
""", "application/vnd.apple.mpegurl")
            return
        if path == "/stalling.m3u8":
            self.send_body("""#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:5
#EXTINF:5.0,
/stall-start.ts
#EXTINF:5.0,
/stall-tail.ts
#EXT-X-ENDLIST
""", "application/vnd.apple.mpegurl")
            return
        if path == "/stall-start.ts":
            data = self.server.media.with_suffix(".ts").read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "video/mp2t")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        if path == "/stall-tail.ts":
            time.sleep(30)
            self.send_body("deliberate stall", status=503)
            return
        if path.startswith("/fail"):
            self.send_body("deliberate QA failure", status=404)
            return
        if path in ["/media.mp4", "/slow.mp4"]:
            if self.server.mode == "release-offline" and urlparse(self.path).query == "release=1":
                self.send_body("deliberate media failure", status=503)
                return
            if path == "/slow.mp4":
                time.sleep(12)
            self.serve_media()
            return
        if path == "/slow-list.m3u":
            time.sleep(10)
        if path == "/mutable.m3u":
            mode = self.server.mode
            if mode == "http503":
                self.send_body("deliberate update failure", status=503)
                return
            if mode == "html":
                self.send_body("<html>not a playlist</html>", "text/html")
                return
            if mode == "empty":
                self.send_body("#EXTM3U\n")
                return
            if mode == "hls":
                self.send_body("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nsegment.ts\n")
                return
        base = f"http://10.0.2.2:{self.server.server_port}"
        lines = ["#EXTM3U x-tvg-url=\"https://example.org/qa.xml\""]

        def add(name, endpoint, group="受控测试"):
            lines.extend([f'#EXTINF:-1 group-title="{group}",{name}', base + endpoint])

        if path == "/other.m3u":
            add("测试节目 A", "/media.mp4?origin=other")
            add("测试节目 G", "/media.mp4?program=g")
        elif path == "/many.m3u":
            for number in range(1, 201):
                add(f"节目 {number:03d}", f"/media.mp4?program={number}")
        elif path in ["/main.m3u", "/mutable.m3u", "/slow-list.m3u", "/hold-list.m3u"]:
            add("测试节目 A", "/fail-a.mp4", "组一")
            add("测试节目 A", "/media.mp4?program=a", "组二")
            add("测试节目 B", "/media.mp4?program=b")
            add("测试节目 C", "/fail-c1.mp4")
            add("测试节目 C", "/fail-c2.mp4")
            for number in range(1, 10):
                add("测试节目 D", f"/fail-d{number}.mp4")
            add("测试节目 D", "/media.mp4?program=d")
            add("测试节目 D", "/media.mp4?unused=after-first-success")
            add("测试节目 E", "/slow.mp4")
            add("测试节目 E", "/media.mp4?program=e")
        else:
            self.send_body("not found", status=404)
            return
        if held:
            self.wfile.write(("\n".join(lines[1:]) + "\n").encode("utf-8"))
            with self.server.event_lock:
                self.server.events.append({"path": self.path, "at": time.time(), "event": "completed"})
        else:
            self.send_body("\n".join(lines) + "\n", "application/vnd.apple.mpegurl")

    def serve_media(self):
        size = self.server.media.stat().st_size
        start = 0
        end = size - 1
        match = re.match(r"bytes=(\d+)-(\d*)", self.headers.get("Range", ""))
        if match:
            start = int(match.group(1))
            end = min(int(match.group(2)) if match.group(2) else end, end)
        if start >= size:
            self.send_body("range not satisfiable", status=416)
            return
        self.send_response(206 if match else 200)
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if match:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        with self.server.media.open("rb") as source:
            source.seek(start)
            remaining = end - start + 1
            while remaining:
                block = source.read(min(65536, remaining))
                if not block:
                    break
                self.wfile.write(block)
                remaining -= len(block)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--media", type=Path, required=True)
    parser.add_argument("--port", type=int, default=18764)
    arguments = parser.parse_args()
    FixtureServer(("127.0.0.1", arguments.port), arguments.media).serve_forever()
