"""Isolated local TV QA playlists and a generated, rights-free test signal."""

import argparse
from datetime import datetime, timedelta, timezone
from http.client import HTTPConnection
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
        if path == "/midnight.xml":
            zone = timezone(timedelta(minutes=int(parse_qs(urlparse(self.path).query).get("offset", ["480"])[0])))
            day = parse_qs(urlparse(self.path).query).get("date", [datetime.now(zone).strftime("%Y-%m-%d")])[0]
            midnight = datetime.strptime(day, "%Y-%m-%d").replace(tzinfo=zone)
            entries = [
                ("QA Yesterday Only", -120, -60), ("QA Entering Midnight", -60, 60),
                ("QA Morning", 60, 120), ("QA Daytime", 120, 1380),
                ("QA Leaving Midnight", 1380, 1500), ("QA Tomorrow Only", 1500, 1560),
            ]

            def stamp(minutes):
                return (midnight + timedelta(minutes=minutes)).strftime("%Y%m%d%H%M%S %z")

            rows = "".join(f'<programme channel="101" start="{stamp(a)}" stop="{stamp(b)}">'
                           f'<title>{title}</title></programme>' for title, a, b in entries)
            self.send_body('<tv><channel id="101"><display-name>QA Release</display-name></channel>'
                           + rows + "</tv>", "application/xml")
            return
        if path == "/guide.xml":
            self.send_response(302)
            self.send_header("Location", "/guide-data.xml")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if path == "/many-failed-lines.m3u":
            rows = "".join(f"#EXTINF:-1,QA Failed Lines\n/fail-line-{line}.mp4\n" for line in range(16))
            self.send_body("#EXTM3U\n" + rows, "application/vnd.apple.mpegurl")
            return
        if path == "/tune.m3u":
            self.send_body("""#EXTM3U
#EXTINF:-1,QA Tune Direct
/media.mp4?tune=direct
#EXTINF:-1,QA Tune Recovery
/fail-tune.mp4
#EXTINF:-1,QA Tune Recovery
/media.mp4?tune=backup
""", "application/vnd.apple.mpegurl")
            return
        if path == "/release.m3u":
            if self.server.mode == "release-offline":
                self.send_body("deliberate playlist failure", status=503)
                return
            self.send_body("""#EXTM3U
#EXTINF:-1 tvg-id="101",QA Release
/fail-release.mp4
#EXTINF:-1 tvg-id="101",QA Release
/media.mp4?release=1
#EXTINF:-1,QA History
/media.mp4?history=1
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
        if path == "/media.mp4":
            self.serve_media()
            return
        self.send_body("not found", status=404)

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


class ForwardProxy(FixtureHandler):
    def do_GET(self):
        url = urlparse(self.path)
        if url.scheme != "http" or url.netloc != "qa-iptv.test":
            self.send_body("Proxy accepts only the QA fixture host", status=403)
            return
        with self.server.event_lock:
            self.server.events.append({"path": self.path, "at": time.time(), "event": "proxy"})
        target = url.path + ("?" + url.query if url.query else "")
        connection = HTTPConnection("127.0.0.1", self.server.origin_port, timeout=40)
        try:
            headers = {key: value for key, value in self.headers.items() if key.lower() == "range"}
            connection.request("GET", target, headers=headers)
            upstream = connection.getresponse()
            self.send_response(upstream.status)
            for key, value in upstream.getheaders():
                if key.lower() in {"content-length", "content-type", "accept-ranges", "content-range"}:
                    self.send_header(key, value)
            self.end_headers()
            while block := upstream.read(65536):
                self.wfile.write(block)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass
        finally:
            connection.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--media", type=Path, required=True)
    parser.add_argument("--port", type=int, default=18764)
    parser.add_argument("--proxy-port", type=int, default=18766)
    args = parser.parse_args()
    with FixtureServer(("127.0.0.1", args.port), args.media) as origin:
        with ThreadingHTTPServer(("127.0.0.1", args.proxy_port), ForwardProxy) as proxy:
            proxy.daemon_threads = True
            proxy.origin_port = args.port
            proxy.events, proxy.event_lock = origin.events, origin.event_lock
            threading.Thread(target=origin.serve_forever, daemon=True).start()
            try:
                proxy.serve_forever()
            finally:
                origin.shutdown()
