"""Startup regressions on a disposable Android emulator (debug APK required).

Usage: python tools/smoke_startup.py --adb <adb.exe> --serial emulator-5554
This replaces test preferences temporarily, then restores the original file.
"""
import argparse
import subprocess
import tempfile
import time
from pathlib import Path
import xml.etree.ElementTree as ET


PACKAGE = "com.dongting.player"
PREFS = "shared_prefs/dongting_player.xml"
REMOTE = "/data/local/tmp/didong-startup-test.xml"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Only disposable emulator devices are supported.")

    def adb(*parts, check=True):
        return subprocess.run([args.adb, "-s", args.serial, *parts],
                              capture_output=True, check=check, timeout=45)

    def shell(*parts, check=True):
        return adb("shell", *parts, check=check).stdout.decode("utf-8", errors="replace")

    def stop():
        shell("am", "force-stop", PACKAGE)

    def write_prefs(data):
        stop()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "prefs.xml"
            path.write_bytes(data)
            adb("push", str(path), REMOTE)
        shell("run-as", PACKAGE, "mkdir", "-p", "shared_prefs")
        shell("run-as", PACKAGE, "cp", REMOTE, PREFS)

    def start():
        result = shell("am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
        assert "Status: ok" in result, result

    def verify_screen(expected):
        shell("uiautomator", "dump", "/data/local/tmp/didong-startup-ui.xml")
        content = shell("cat", "/data/local/tmp/didong-startup-ui.xml")
        root = ET.fromstring(content)
        texts = "\n".join(node.get("text", "") for node in root.iter("node"))
        assert expected in texts, texts
        return texts

    def verify_no_crash():
        crash = shell("logcat", "-d", "-b", "crash")
        assert PACKAGE not in crash, crash

    # Verify access before making any changes. Never run this against a real phone.
    shell("run-as", PACKAGE, "pwd")
    stop()
    backup = adb("shell", "run-as", PACKAGE, "cat", PREFS, check=False)
    try:
        for name, values in [
            ("fresh startup", ""),
            ("historical crash recovery", '<string name="lastCrashTrace">previous crash</string>'),
        ]:
            write_prefs(("<map>" + values + "</map>").encode())
            shell("pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS", check=False)
            shell("logcat", "-b", "crash", "-c")
            start()
            verify_screen("地洞播放器")
            before = shell("pidof", PACKAGE).strip()
            assert before, "Application exited"
            shell("input", "keyevent", "KEYCODE_HOME")
            start()
            verify_screen("地洞播放器")
            assert shell("pidof", PACKAGE).strip() == before, "Unexpected process restart"
            verify_no_crash()
            print("PASS:", name, "and background/foreground", flush=True)

        # A deliberately invalid preference fails before bgPlayer is constructed.
        write_prefs(b'<map><string name="loopMode">invalid</string></map>')
        shell("logcat", "-b", "crash", "-c")
        start()
        verify_screen("启动异常诊断（请截图或复制发给我）")
        before = shell("pidof", PACKAGE).strip()
        shell("input", "keyevent", "KEYCODE_BACK")
        time.sleep(1)
        verify_no_crash()
        assert shell("pidof", PACKAGE).strip() == before, "Diagnostic exit crashed"
        print("PASS: partial initialization and diagnostic exit", flush=True)
    finally:
        stop()
        if backup.returncode == 0:
            write_prefs(backup.stdout)
        else:
            shell("run-as", PACKAGE, "rm", "-f", PREFS)
        shell("rm", "-f", REMOTE, "/data/local/tmp/didong-startup-ui.xml")


if __name__ == "__main__":
    main()
