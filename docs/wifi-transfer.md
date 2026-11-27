# Wi-Fi 文件传输（Android）

入口：主界面「列表/书架 → Wi-Fi 文件传输」，或「设置中心 → 云端文件 → Wi-Fi 文件传输」。

## 使用方法

1. 手机与电脑（或另一部手机）连接同一 Wi-Fi，打开手机的传输页面，点击「开启 Wi-Fi 传输」。
2. 点击「复制访问地址」，在另一台设备的浏览器地址栏打开完整地址。地址中的随机路径是本次访问凭证，不要只输入 IP 和端口。
3. 浏览器选择一个或多个音乐、视频、电子书，点击「开始上传」。页面显示单文件进度，并支持取消上传。
4. 传完后在手机点击「完成并入库」，或直接返回，已完成的本次文件会加入现有媒体列表/电子书架。影音可直接播放，书籍沿用现有离线解析、章节、进度和朗读功能。
5. 手机页面保留已接收记录。点选单个文件可打开，也可用「导入全部已收文件」重新入库。浏览器列表的「下载」可把本应用通过 Wi-Fi 收到的文件传回当前设备。

## 访问与存储

- 默认关闭，必须手动开启。停止、退出页面、切到后台或锁屏使页面停止时关闭服务器；再次开启会更换端口和随机访问路径。传输过程中保持手机页面在前台，屏幕会保持常亮。
- 采用局域网 HTTP 直传，没有云端中转，也不需要网盘账号。HTTP 不加密，请只在可信任的局域网中使用，并妥善保管临时完整地址。
- 只接受私有/本地 IPv4 连接，展示 Wi-Fi、以太网或手机热点接口的地址，不展示蜂窝网络与 VPN 地址。不自动创建热点。
- 不提供公网远程访问、自动打开路由器端口或后台常驻服务。路由器的 AP 隔离、访客网络隔离或防火墙可能阻止设备互访。
- 浏览器只能访问本应用通过 Wi-Fi 接收的文件，不开放手机其他目录、WebDAV 账号或应用数据库。拒绝无效访问路径、外站 Origin、非 IP Host、目录穿越和重复/歧义请求头。
- 接收文件保存在应用私有 no-backup 目录，不会进入应用设置备份或系统自动备份；卸载应用会删除这些文件。同名文件分别存放，不覆盖已有文件。
- 文件完整写入后才提交到已收记录。中断、取消或关闭服务不会把未传完的文件入库；已完成的文件仍保留。
- 当前支持选择多个文件上传，不提供文件夹递归上传或断点续传。每次只接收一个正在上传的文件；其他设备并发上传会收到稍后重试提示。
- 电子书最大 64 MB，影音最大 32 GB，同时保留至少 16 MB 存储余量；最多保留 2000 个已收文件。手机页面展示最近 100 个，浏览器和「导入全部」可访问全部记录。
- 「清理全部已收文件」只清理 Wi-Fi 接收的文件。请先停止相关播放/朗读；清理后原有书架/列表条目会失效，需要移除或重新传输。
- 本期仅 Android 提供 Wi-Fi 接收服务；Windows 随包版本仍为免安装网页版。

## 验证

纯 Java 服务器回归：

```powershell
javac -encoding UTF-8 -d build/wifi-tests app/src/main/java/com/dongting/player/WifiTransferServer.java tools/tests/WifiTransferServerTest.java
java -cp build/wifi-tests com.dongting.player.WifiTransferServerTest
```

覆盖上传/下载、中文名、同名保留、Range、随机会话、Host/Origin 边界、格式和大小限制、不完整文件保护、停止与重开。

Android 集成（只在可丢弃模拟器运行，会添加测试文件与书架/媒体条目）：

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug --console=plain
python tools/test_wifi_device.py --adb C:\路径\到\adb.exe --serial emulator-5580
```

验证默认关闭、手动开启、手机实际 HTTP 上传、中文同名文件、重开换凭证、离页关闭、返回主界面入库、音频播放和离线电子书解析/打开。结果与截图保存在 `build/wifi-device-results.txt`、`build/wifi-transfer.png`。

浏览器人工/Playwright 验收可使用同一生产服务器的本地 JVM fixture：

```powershell
javac -encoding UTF-8 -d build/wifi-tests app/src/main/java/com/dongting/player/WifiTransferServer.java tools/tests/WifiTransferBrowserFixture.java
java -cp build/wifi-tests com.dongting.player.WifiTransferBrowserFixture
```

该测试端点最多运行十分钟，地址写入 `build/wifi-browser-url.txt`，测试文件保存在 `build/wifi-browser-files/`；勿用于共享真实文件。
