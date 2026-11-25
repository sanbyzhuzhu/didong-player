"""Exercise real reader service/UI with no-range test TTS and local fake MultiTTS.

Build assembleDebug assembleDebugAndroidTest first. Only disposable emulators allowed.
"""
import argparse
import io
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import subprocess
import tempfile
import threading
from urllib.parse import parse_qs, urlparse
import wave


ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.dongting.player"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Use a disposable emulator, never a real phone.")

    def adb(*parts, check=True, timeout=90):
        return subprocess.run([args.adb, "-s", args.serial, *parts],
                              capture_output=True, check=check, timeout=timeout)

    def shell(*parts, check=True):
        return adb("shell", *parts, check=check).stdout.decode("utf-8", "replace")

    backup = adb("shell", "run-as", PACKAGE, "cat", "shared_prefs/dongting_player.xml", check=False)
    engine = shell("settings", "get", "secure", "tts_default_synth").strip()
    stream = io.BytesIO()
    with wave.open(stream, "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(16000)
        audio.writeframes(bytes(80000))  # 2.5 seconds; no text/word timestamps.
    payload = stream.getvalue()
    requests = []

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            text = parse_qs(urlparse(self.path).query).get("text", [""])[0]
            requests.append(text)
            self.send_response(200)
            self.send_header("Content-Type", "audio/wav")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            try:
                self.wfile.write(payload)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("0.0.0.0", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    endpoint = f"http://10.0.2.2:{server.server_port}/forward"
    report = []
    try:
        for path in ["app/build/outputs/apk/debug/app-debug.apk",
                     "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]:
            result = adb("install", "-r", str(ROOT / path))
            assert b"Success" in result.stdout, result.stdout
        shell("pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
        shell("settings", "put", "secure", "tts_default_synth", PACKAGE + ".test")
        assert shell("settings", "get", "secure", "tts_default_synth").strip() == PACKAGE + ".test"
        for provider in ["system", "multitts"]:
            shell("am", "force-stop", PACKAGE)
            shell("logcat", "-c")
            result = shell("am", "instrument", "-w", "-e", "provider", provider,
                           "-e", "endpoint", endpoint,
                           PACKAGE + ".test/com.dongting.player.ReaderInstrumentation")
            report.append(result)
            (ROOT / f"build/reader-{provider}-logcat.txt").write_text(shell("logcat", "-d"), encoding="utf-8")
            print(result, flush=True)
            assert "PASS:" in result and "FAIL:" not in result, result
            crash = shell("logcat", "-d", "-b", "crash")
            assert PACKAGE not in crash, crash
            adb("pull", f"/sdcard/Android/data/{PACKAGE}/files/reader-{provider}.png",
                str(ROOT / f"build/reader-{provider}.png"))
            if provider == "system":
                messages = shell("logcat", "-d", "-s", "ReaderTestTts:I", "*:S")
                report.append(messages)
                assert "第一句，现在开始逐句跟读。" in messages
                assert "第二句，画面应该同步切换！" in messages
                assert "第三句，暂停以后也能继续。" in messages
        expected = {"第一句，现在开始逐句跟读。", "第二句，画面应该同步切换！",
                    "第三句，暂停以后也能继续。", "第四句，测试结束。"}
        assert requests and all(text in expected for text in requests), requests
        print("PASS: MultiTTS received only individual sentences:", requests, flush=True)
        report.append(repr(requests))
    finally:
        server.shutdown()
        shell("am", "force-stop", PACKAGE, check=False)
        if engine and engine != "null":
            shell("settings", "put", "secure", "tts_default_synth", engine)
        else:
            shell("settings", "delete", "secure", "tts_default_synth")
        if backup.returncode == 0:
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "prefs.xml"
                path.write_bytes(backup.stdout)
                adb("push", str(path), "/data/local/tmp/didong-reader-prefs.xml")
                shell("run-as", PACKAGE, "cp", "/data/local/tmp/didong-reader-prefs.xml",
                      "shared_prefs/dongting_player.xml")
            shell("rm", "-f", "/data/local/tmp/didong-reader-prefs.xml")
        else:
            shell("run-as", PACKAGE, "rm", "-f", "shared_prefs/dongting_player.xml", check=False)
        (ROOT / "build/reader-device-results.txt").write_text("\n".join(report), encoding="utf-8")


if __name__ == "__main__":
    main()
