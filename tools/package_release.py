"""Build and package APK, working-tree sources and the Windows browser edition."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from zipfile import ZIP_DEFLATED, ZipFile
from validate_update_feed import validate as validate_update_feed


ROOT = Path(__file__).resolve().parents[1]
NOTES_NAME = "release-notes.md"
EXCLUDED_DIRS = {".git", ".gradle", ".omx", ".idea", ".vscode", "build",
                 "release", "captures", "__pycache__", "node_modules", ".venv"}


def source_files():
    # Read current files, including uncommitted additions, rather than HEAD blobs.
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=ROOT, check=True, capture_output=True)
    paths = {ROOT / name for name in result.stdout.decode("utf-8").split("\0") if name}
    paths.update((ROOT / "tools").rglob("*.py"))
    for path in sorted(paths):
        relative = path.relative_to(ROOT)
        if any(part in EXCLUDED_DIRS for part in relative.parts):
            continue
        if path.name == "local.properties" or path.name.startswith(".env"):
            continue
        if path.suffix.lower() in {".apk", ".aab", ".zip", ".keystore", ".jks", ".pem", ".key"}:
            continue
        if path.is_file() and not path.is_symlink():
            yield path, relative


def check_zip(path, required):
    with ZipFile(path) as archive:
        bad = archive.testzip()
        if bad:
            raise RuntimeError(f"Invalid ZIP entry: {bad}")
        missing = set(required) - set(archive.namelist())
        if missing:
            raise RuntimeError(f"Missing package files: {sorted(missing)}")


def changelog_notes(version):
    text = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
    section = re.search(rf"^## V{re.escape(version)}[^\n]*\n(.*?)(?=^## |\Z)",
                        text, re.M | re.S)
    return section.group(1).strip() if section else "详见源码包内 CHANGELOG.md。"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--description", required=True, help="Short folder description")
    parser.add_argument("--offline", action="store_true", help="Use cached Gradle dependencies")
    args = parser.parse_args()
    description = args.description.strip()
    if not description or re.search(r'[<>:"/\\|?*\x00-\x1f]', description) or description.endswith("."):
        parser.error("Description must be a valid Windows folder name component.")

    gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
    version = re.search(r'versionName\s+"([\w.\-]+)"', gradle).group(1)
    version_code = int(re.search(r"versionCode\s+(\d+)", gradle).group(1))
    validate_update_feed(ROOT)
    release_root = ROOT / "release"
    release_root.mkdir(exist_ok=True)
    destination = release_root / f"v{version}_{description}"
    if destination.exists():
        parser.error(f"Release already exists; choose a new version or description: {destination}")

    command = (["cmd", "/c", "gradlew.bat"] if os.name == "nt" else ["sh", "./gradlew"])
    command += ["assembleDebug", "--console=plain"]
    if args.offline:
        command.append("--offline")
    subprocess.run(command, cwd=ROOT, check=True)
    output = ROOT / "app/build/outputs/apk/debug"
    metadata = json.loads((output / "output-metadata.json").read_text(encoding="utf-8"))
    elements = metadata["elements"]
    if len(elements) != 1:
        raise RuntimeError("Expected one universal debug APK; review split APK packaging.")
    element = elements[0]
    if element["versionName"] != version or element["versionCode"] != version_code:
        raise RuntimeError("APK version metadata does not match app/build.gradle")

    prefix = f"didong-player-v{version}"
    with tempfile.TemporaryDirectory(prefix=".packaging-", dir=release_root) as temporary:
        stage = Path(temporary) / destination.name
        stage.mkdir()
        apk = stage / f"{prefix}-debug.apk"
        shutil.copy2(output / element["outputFile"], apk)
        check_zip(apk, ["AndroidManifest.xml", "classes.dex"])

        source_name = f"{prefix}-source"
        source_zip = stage / f"{source_name}.zip"
        files = list(source_files())
        with ZipFile(source_zip, "w", ZIP_DEFLATED) as archive:
            for path, relative in files:
                archive.write(path, f"{source_name}/{relative.as_posix()}")
        check_zip(source_zip, [f"{source_name}/{name}" for name in [
            "app/build.gradle", "app/src/main/AndroidManifest.xml", "gradlew.bat",
            "gradle/wrapper/gradle-wrapper.jar", "tools/package_release.py", "AGENTS.md"]])

        desktop_included = (ROOT / "windows/DidongPlayer.html").is_file()
        if desktop_included:
            desktop_root = f"{prefix}-windows-portable"
            desktop_zip = stage / f"{desktop_root}.zip"
            with ZipFile(desktop_zip, "w", ZIP_DEFLATED) as archive:
                for path in sorted((ROOT / "windows").rglob("*")):
                    if path.is_file() and not path.is_symlink():
                        archive.write(path, f"{desktop_root}/{path.relative_to(ROOT / 'windows').as_posix()}")
                for name in ["LICENSE", "NOTICE"]:
                    archive.write(ROOT / name, f"{desktop_root}/{name}")
                archive.writestr(f"{desktop_root}/使用说明.txt",
                    f"地洞播放器 Windows 免安装网页版（随 V{version} 发布）\n\n"
                    "请先完整解压，再双击“启动地洞Windows版.bat”，或使用 Edge/Chrome 打开 DidongPlayer.html。\n"
                    "这是现有浏览器原型，并非原生 EXE，功能与 Android 版存在差异。\n"
                    "文件授权通常不会永久保存，下次打开可能需要重新选择文件夹。\n")
            check_zip(desktop_zip, [f"{desktop_root}/DidongPlayer.html",
                                   f"{desktop_root}/启动地洞Windows版.bat"])

        notes = (f"# 地洞播放器 V{version} — {description}\n\n"
                 f"Android versionCode：{version_code}\n\n"
                 "## 文件说明\n\n"
                 f"- `{apk.name}`：Android 调试签名 APK，Android 8.0 及以上。\n"
                 f"- `{source_zip.name}`：本次打包时工作区的源码快照，包含未提交修改。\n"
                 "- `SHA256SUMS.txt`：发布文件校验和。\n"
                 "- `release-manifest.json`：版本和文件清单。\n")
        if desktop_included:
            notes += f"- `{desktop_zip.name}`：Windows 免安装网页版，解压后启动，非原生 EXE。\n"
        notes += ("\n## 本版变更\n\n" + changelog_notes(version) + "\n\n"
                  "## 源码构建\n\n"
                  "解压源码包，安装 JDK 17+ 和 Android SDK。配置 ANDROID_HOME，或创建 local.properties 指定 sdk.dir。\n"
                  "在源码根目录执行 `gradlew.bat assembleDebug`。\n"
                  "源码包不包含本机 SDK 路径、Git 历史、构建缓存、历史发布包及签名私钥。\n"
                  "重新构建的 debug APK 使用构建机器的调试签名，跨机器安装可能存在签名差异。\n\n"
                  "## 下次发布\n\n"
                  "先更新 app/build.gradle 的 versionCode/versionName 及 CHANGELOG.md，执行：\n\n"
                  '`python tools/package_release.py --description "本次说明"`\n')
        (stage / NOTES_NAME).write_text(notes, encoding="utf-8-sig")
        manifest = {"version": version, "versionCode": version_code,
                    "description": description, "androidBuildType": "debug",
                    "source": "current working tree, including uncommitted changes",
                    "sourceFileCount": len(files),
                    "desktop": "Windows browser prototype" if desktop_included else None,
                    "files": sorted(path.name for path in stage.iterdir())}
        (stage / "release-manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        checksums = []
        for path in sorted(stage.iterdir()):
            checksum = hashlib.sha256(path.read_bytes()).hexdigest()
            checksums.append(f"{checksum}  {path.name}")
        (stage / "SHA256SUMS.txt").write_text("\n".join(checksums) + "\n", encoding="utf-8")
        # Publish the verified folder only when all artifacts are complete.
        stage.rename(destination)
    print(f"Release ready: {destination}", flush=True)
    for path in sorted(destination.iterdir()):
        print(f"  {path.name} ({path.stat().st_size:,} bytes)", flush=True)


if __name__ == "__main__":
    main()
