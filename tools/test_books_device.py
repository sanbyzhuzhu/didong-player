"""Test real Android ebook import and TOC navigation on a disposable emulator."""
import argparse
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.dongting.player"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Use a disposable emulator, not a real phone")

    def adb(*parts, check=True):
        return subprocess.run([args.adb, "-s", args.serial, *parts], capture_output=True, check=check, timeout=120)

    def shell(*parts, check=True):
        return adb("shell", *parts, check=check).stdout.decode("utf-8", "replace")

    backup = adb("shell", "run-as", PACKAGE, "cat", "shared_prefs/dongting_player.xml", check=False)
    engine = shell("settings", "get", "secure", "tts_default_synth").strip()
    try:
        for file in ["app/build/outputs/apk/debug/app-debug.apk",
                     "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]:
            assert b"Success" in adb("install", "-r", str(ROOT / file)).stdout
        shell("pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
        shell("settings", "put", "secure", "tts_default_synth", PACKAGE + ".test")
        shell("am", "force-stop", PACKAGE)
        shell("run-as", PACKAGE, "mkdir", "-p", "files/book-test-fixtures")
        names = ["epub3.epub", "epub2.epub", "sample.docx", "sample.md", "sample.html"]
        for name in names + ["legacy.epub"]:
            source = ROOT / "build/book-fixtures" / ("epub3.epub" if name == "legacy.epub" else name)
            adb("push", str(source), "/data/local/tmp/didong-book-fixture")
            shell("run-as", PACKAGE, "cp", "/data/local/tmp/didong-book-fixture", "files/book-test-fixtures/" + name)
        shell("logcat", "-c")
        result = shell("am", "instrument", "-w", "-e", "suite", "books",
                       PACKAGE + ".test/com.dongting.player.ReaderInstrumentation", check=False)
        (ROOT / "build/book-device-results.txt").write_text(result, encoding="utf-8")
        (ROOT / "build/book-device-logcat.txt").write_text(shell("logcat", "-d"), encoding="utf-8")
        print(result, flush=True)
        if "FAIL:" in result:
            adb("pull", f"/sdcard/Android/data/{PACKAGE}/files/book-catalog-failure.png",
                str(ROOT / "build/book-catalog-failure.png"), check=False)
        assert "PASS:" in result and "FAIL:" not in result, result
        assert PACKAGE not in shell("logcat", "-d", "-b", "crash")
        adb("pull", f"/sdcard/Android/data/{PACKAGE}/files/book-catalog.png", str(ROOT / "build/book-catalog.png"))
    finally:
        shell("am", "force-stop", PACKAGE, check=False)
        if engine and engine != "null":
            shell("settings", "put", "secure", "tts_default_synth", engine)
        else:
            shell("settings", "delete", "secure", "tts_default_synth")
        if backup.returncode == 0:
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "prefs.xml"
                path.write_bytes(backup.stdout)
                adb("push", str(path), "/data/local/tmp/didong-book-prefs")
                shell("run-as", PACKAGE, "cp", "/data/local/tmp/didong-book-prefs", "shared_prefs/dongting_player.xml")
        else:
            shell("run-as", PACKAGE, "rm", "-f", "shared_prefs/dongting_player.xml", check=False)
        shell("rm", "-f", "/data/local/tmp/didong-book-fixture", "/data/local/tmp/didong-book-prefs")


if __name__ == "__main__":
    main()
