# 项目工作约定

@C:\Users\Administrator\.codex\RTK.md

## 版本发布

- 用户要求：以后发版统一放在 `E:\Anti\didongplayer\release\v版本号_简短说明\`。
- 每版至少包含 APK、当前工作区的完整源码 ZIP、发布说明和 SHA256 校验文件；项目有桌面版本时一并打包，并如实标注版本形态。
- 优先使用 `python tools/package_release.py --description "本次发布说明"`，版本号自动读取 `app/build.gradle`，默认重新构建 APK。
- 源码必须包含本次修改及未提交的新源码，不能只用 `git archive HEAD`；排除 Git 元数据、缓存、构建输出、旧发布包、本机 SDK 配置及签名私钥。
- 当前 Windows 产品为 `windows/` 内的免安装网页版，不要标注成原生 EXE。
- 不覆盖已有同名版本目录，不删除历史发布包。完成后校验 ZIP、版本元数据与校验和，并告知实际输出目录。

## 静默更新清单

- V0.0.21 起，`updates/android.json` 是 Android 客户端静默检查更新的公开清单，GitHub 与 Gitee 应同步同一份内容。
- 发新版时，清单的 versionCode、versionName 必须与 `app/build.gradle` 一致，releaseUrl 必须指向本仓库对应版本的 GitHub 发布页。
- 打包和 CI 会运行 `tools/validate_update_feed.py` 校验。发布附件就绪后，核对公开清单和发布页都可读取，再宣布更新完成。
- 更新功能只后台检查，红点只出现在设置页底部；不得加入自动弹窗、强制更新、自动下载/安装或停止播放的行为。
