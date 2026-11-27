"""WebDAV integration suite against a disposable Android emulator and an authenticated fixture server."""
import argparse
import base64
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO
from pathlib import Path
import subprocess
import threading
from urllib.parse import quote, unquote, urlsplit
import wave
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]
AUTH = "Basic " + base64.b64encode(b"reader:test-webdav-secret").decode()
TEXT = "云端朗读第一句。第二句，下载后可以离线继续。\n第二段正文。".encode()
buffer = BytesIO()
with wave.open(buffer, "wb") as wav:
    wav.setnchannels(1)
    wav.setsampwidth(2)
    wav.setframerate(16000)
    wav.writeframes(b"\0\0" * 16000 * 180)
WAV = buffer.getvalue()
events = {"authenticated_gets": 0, "ranges": 0, "public_gets": 0, "leaked_auth": 0, "flaky": 0}


def response(path, directory=False, size=None):
    props = "<d:resourcetype>" + ("<d:collection/>" if directory else "") + "</d:resourcetype>"
    if size is not None:
        props += f"<d:getcontentlength>{size}</d:getcontentlength>"
    return (f"<d:response><d:href>{escape(quote(path, safe='/'))}</d:href>"
            f"<d:propstat><d:prop>{props}</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def send(self, code, data=b"", mime="text/plain", headers=None):
        self.send_response(code)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        try:
            self.wfile.write(data)
        except (ConnectionError, OSError):
            pass

    def authorized(self):
        if self.headers.get("Authorization") == AUTH:
            return True
        self.send(401, headers={"WWW-Authenticate": 'Basic realm="WebDAV test"'})
        return False

    def do_PROPFIND(self):
        self.rfile.read(int(self.headers.get("Content-Length", "0")))
        if not self.authorized():
            return
        if self.headers.get("Depth") != "1":
            self.send(400)
            return
        path = unquote(urlsplit(self.path).path)
        if path == "/dav/outside/":
            self.send(302, headers={"Location": "/public/"})
            return
        if path == "/dav/":
            body = (response(path, True) + response("/dav/子目录/", True)
                    + response("/dav/云端 书籍.txt", size=len(TEXT))
                    + response("/dav/sample.wav", size=len(WAV))
                    + response("/dav/redirect.wav", size=len(WAV)))
        elif path == "/dav/子目录/":
            body = response(path, True) + response("/dav/子目录/第二本.txt", size=len(TEXT))
        else:
            self.send(404)
            return
        self.send(207, ('<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:">'
                        + body + "</d:multistatus>").encode(), "application/xml; charset=utf-8")

    def do_GET(self):
        path = unquote(urlsplit(self.path).path)
        if path.startswith("/public/"):
            events["public_gets"] += 1
            if self.headers.get("Authorization"):
                events["leaked_auth"] += 1
                self.send(403)
                return
        elif not self.authorized():
            return
        else:
            events["authenticated_gets"] += 1
        if path == "/dav/redirect.wav":
            self.send(302, headers={"Location": "/public/sample.wav"})
            return
        if path == "/dav/flaky.txt":
            events["flaky"] += 1
            if events["flaky"] > 1:
                self.send_response(200)
                self.send_header("Content-Length", "1024")
                self.end_headers()
                self.wfile.write(b"incomplete")
                self.close_connection = True
                return
        if path.endswith(".txt"):
            self.send(200, TEXT, "text/plain; charset=utf-8")
            return
        if path.endswith(".wav"):
            data = WAV
            headers = {"Accept-Ranges": "bytes"}
            code = 200
            value = self.headers.get("Range", "")
            if value.startswith("bytes="):
                events["ranges"] += 1
                start, _, end = value[6:].partition("-")
                start, end = int(start), int(end) if end else len(data) - 1
                if start >= len(data):
                    self.send(416, headers={"Content-Range": f"bytes */{len(data)}"})
                    return
                headers["Content-Range"] = f"bytes {start}-{end}/{len(data)}"
                data, code = data[start:end + 1], 206
            self.send(code, data, "audio/wav", headers)
            return
        self.send(404)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Use only a disposable emulator: this test modifies application settings and test credentials.")
    adb = [args.adb, "-s", args.serial]

    def command(*parts, **kwargs):
        return subprocess.run(adb + list(parts), check=True, capture_output=True, text=True, encoding="utf-8", errors="replace", **kwargs).stdout

    server = ThreadingHTTPServer(("127.0.0.1", 18776), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        command("install", "-r", str(ROOT / "app/build/outputs/apk/debug/app-debug.apk"))
        command("install", "-r", str(ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
        command("reverse", "tcp:18776", "tcp:18776")
        output = command("shell", "am", "instrument", "-w", "-e", "suite", "webdav",
                         "com.dongting.player.test/com.dongting.player.ReaderInstrumentation", timeout=180)
        (ROOT / "build/webdav-device-results.txt").write_text(output, encoding="utf-8")
        print(output)
        if "PASS:" not in output or "FAIL:" in output:
            raise RuntimeError("WebDAV device suite failed; see build/webdav-device-results.txt")
        assert events["authenticated_gets"] >= 4, events
        assert events["ranges"] >= 1, events
        assert events["public_gets"] >= 1 and events["leaked_auth"] == 0, events
        command("pull", "/sdcard/Android/data/com.dongting.player/files/webdav-browser.png", str(ROOT / "build/webdav-browser.png"))
        print("PASS: server observed authenticated downloads/streams, Range requests, and no password forwarded outside DAV root")
        print("Screenshot: build/webdav-browser.png")
    finally:
        server.shutdown()
        server.server_close()
        command("reverse", "--remove", "tcp:18776")


if __name__ == "__main__":
    main()
