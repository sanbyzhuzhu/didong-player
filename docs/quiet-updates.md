# 设置页的静默软件更新（V0.0.21）

先安装本版后，后续版本才能使用此更新提示；旧版 APK 不能在未升级的情况下获得新增入口。

## 使用方式

打开“设置中心”，拉到最下面的“软件更新”。有更新时，只在这个条目旁显示红点，并标明新版版本号。
点击条目后可以查看更新说明，选择“更新”“忽略此版本”或“暂不更新”。

“更新”之后还会要求确认。确认后打开官方 GitHub 发布页，由用户选择下载及系统安装；应用本身不下载 APK、不调用安装器，也不请求安装未知应用权限。
确认页另有“查看 Gitee 说明”，该入口是源码与说明镜像，不能保证提供独立的 Gitee 安装附件。
忽略会持久保存，重新打开应用或手动检查也不会恢复该版本红点；更高版本仍可以显示红点。忽略后仍可点击条目查看并主动更新。

## 后台检查

- 在应用正常完成启动后，以及进入设置页时，使用工作线程尝试检查；不阻塞界面，不参与播放器或朗读服务状态。
- 优先读取 GitHub 的 `updates/android.json` 原始内容；失败后通过 Gitee 公开 Contents API 读取同一个文件并进行 Base64 解码，不需要用户登录或 Token。
- 成功后 24 小时内不重复自动请求；失败后 2 小时内不重复自动请求。设置页“检查更新”可主动重试。
- 不常驻后台，不创建定时服务，不在应用关闭后唤醒设备；检查只是应用运行时的后台网络任务。
- 网络失败或清单损坏时保留可用缓存，仅在设置页内显示状态，不弹窗、不通知，不影响当前版本使用。
- 每个源设置连接和读取超时，限制清单大小；仅接受 HTTPS 的本项目官方发布页及镜像地址。

更新记录保存在独立偏好文件 `dongting_updates.xml`，不会改写播放列表、音效、朗读进度或播放记忆。

## 发版维护

1. 更新 `app/build.gradle` 的 versionCode / versionName 与 CHANGELOG。
2. 同步更新 `updates/android.json`：版本号、说明及对应的 GitHub tag 发布页。
3. 运行 `python tools/validate_update_feed.py`；正常打包和 GitHub CI 也会验证版本一致。
4. 发布并校验 APK、源码、Windows 浏览器包；将清单推送到两个仓库，确认对应发布页已经可访问后，再对外宣布。

不要将私人 API Token、签名私钥或账号密码放进公开清单。客户端只读公开版本信息，没有仓库写权限。

## 回归测试

```powershell
javac -encoding UTF-8 -d build/update-tests app/src/main/java/com/dongting/player/UpdatePolicy.java app/src/main/java/com/dongting/player/UpdateInfo.java tools/tests/UpdatePolicyTest.java
java -cp build/update-tests com.dongting.player.UpdatePolicyTest
.\gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug
python tools/test_updates_device.py --adb C:\路径\到\adb.exe --serial emulator-5554
```

设备测试仅用于可丢弃模拟器。用模拟版本清单覆盖新版、忽略、重开设置、更高版本、断网与 Gitee 回退，并用实际音频播放验证检查及更新界面不打断播放。
浏览器启动被测试监视器拦截，只在二次确认后才应有一次打开行为，不会实际下载或安装软件。

两个仓库和发布页就绪后，可给设备测试脚本追加 `--live`，由实际 Android HTTPS 客户端读取公开更新清单并验证与已安装版本一致。
