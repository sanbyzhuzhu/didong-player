"""Validate the quiet update UI while real audio plays, using a disposable emulator."""
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
    parser.add_argument("--live", action="store_true", help="After publishing, check the actual public update feed")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Use a disposable emulator, never a real phone")

    def adb(*parts, check=True):
        return subprocess.run([args.adb, "-s", args.serial, *parts], capture_output=True, check=check, timeout=120)

    def shell(*parts, check=True):
        return adb("shell", *parts, check=check).stdout.decode("utf-8", "replace")

    shell("am", "force-stop", PACKAGE)
    backups = {name: adb("shell", "run-as", PACKAGE, "cat", "shared_prefs/" + name + ".xml", check=False)
               for name in ["dongting_player", "dongting_updates"]}
    engine = shell("settings", "get", "secure", "tts_default_synth").strip()
    try:
        for path in ["app/build/outputs/apk/debug/app-debug.apk", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]:
            assert b"Success" in adb("install", "-r", str(ROOT / path)).stdout
        shell("pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
        shell("settings", "put", "secure", "tts_default_synth", PACKAGE + ".test")
        shell("logcat", "-c")
        result = shell("am", "instrument", "-w", "-e", "suite", "updates-live" if args.live else "updates",
                       PACKAGE + ".test/com.dongting.player.ReaderInstrumentation", check=False)
        (ROOT / ("build/update-live-results.txt" if args.live else "build/update-device-results.txt")).write_text(result, encoding="utf-8")
        (ROOT / "build/update-device-logcat.txt").write_text(shell("logcat", "-d"), encoding="utf-8")
        print(result, flush=True)
        for name in ["update-red-dot.png", "update-test-failure.png"]:
            adb("pull", f"/sdcard/Android/data/{PACKAGE}/files/{name}", str(ROOT / "build" / name), check=False)
        assert "PASS:" in result and "FAIL:" not in result, result
        assert PACKAGE not in shell("logcat", "-d", "-b", "crash")
    finally:
        shell("am", "force-stop", PACKAGE, check=False)
        (ROOT / "build/update-device-logcat.txt").write_text(shell("logcat", "-d"), encoding="utf-8")
        if engine and engine != "null":
            shell("settings", "put", "secure", "tts_default_synth", engine)
        else:
            shell("settings", "delete", "secure", "tts_default_synth")
        for name, backup in backups.items():
            target = "shared_prefs/" + name + ".xml"
            if backup.returncode == 0:
                with tempfile.TemporaryDirectory() as directory:
                    file = Path(directory) / "prefs.xml"
                    file.write_bytes(backup.stdout)
                    adb("push", str(file), "/data/local/tmp/didong-update-prefs")
                    shell("run-as", PACKAGE, "cp", "/data/local/tmp/didong-update-prefs", target)
            else:
                shell("run-as", PACKAGE, "rm", "-f", target, check=False)
        shell("rm", "-f", "/data/local/tmp/didong-update-prefs")


if __name__ == "__main__":
    main()
