"""Fail a release when the public update manifest and Android version disagree."""
import json
from pathlib import Path
import re


def validate(root):
    root = Path(root)
    gradle = (root / "app/build.gradle").read_text(encoding="utf-8")
    code = int(re.search(r"versionCode\s+(\d+)", gradle).group(1))
    version = re.search(r'versionName\s+"([^\"]+)"', gradle).group(1)
    raw = (root / "updates/android.json").read_bytes()
    assert len(raw) <= 65536, "Update manifest is too large"
    feed = json.loads(raw.decode("utf-8"))
    assert feed.get("schemaVersion") == 1, "Unsupported update manifest schema"
    assert type(feed.get("versionCode")) is int and feed["versionCode"] == code, "Update versionCode differs from Android build"
    assert feed.get("versionName") == version, "Update versionName differs from Android build"
    assert feed.get("releaseUrl") == f"https://github.com/sanbyzhuzhu/didong-player/releases/tag/v{version}", "Invalid official release URL"
    assert feed.get("mirrorUrl") == "https://gitee.com/sanbyzhu/didong-player", "Invalid mirror URL"
    assert isinstance(feed.get("releaseNotes"), str) and len(feed["releaseNotes"]) <= 4000, "Invalid release notes"
    return feed


if __name__ == "__main__":
    feed = validate(Path(__file__).resolve().parents[1])
    print("PASS: public update manifest matches Android V" + feed["versionName"])
