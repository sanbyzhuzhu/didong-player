"""Exercise Wi-Fi receiving, main import and offline playback/reading on a disposable emulator."""
import argparse
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Only use a disposable emulator; this suite adds test files and library entries.")

    def adb(*parts, timeout=120):
        return subprocess.run([args.adb, "-s", args.serial, *parts], check=True, capture_output=True,
                              timeout=timeout, text=True, encoding="utf-8", errors="replace").stdout

    for apk in ["app/build/outputs/apk/debug/app-debug.apk",
                "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]:
        adb("install", "-r", str(ROOT / apk))
    adb("shell", "pm", "grant", "com.dongting.player", "android.permission.POST_NOTIFICATIONS")
    adb("shell", "am", "force-stop", "com.dongting.player")
    output = adb("shell", "am", "instrument", "-w", "-e", "suite", "wifi",
                 "com.dongting.player.test/com.dongting.player.ReaderInstrumentation", timeout=180)
    (ROOT / "build/wifi-device-results.txt").write_text(output, encoding="utf-8")
    print(output)
    if "PASS:" not in output or "FAIL:" in output:
        raise RuntimeError("Wi-Fi device test failed; see build/wifi-device-results.txt")
    adb("pull", "/sdcard/Android/data/com.dongting.player/files/wifi-transfer.png", str(ROOT / "build/wifi-transfer.png"))
    print("Screenshot: build/wifi-transfer.png")


if __name__ == "__main__":
    main()
