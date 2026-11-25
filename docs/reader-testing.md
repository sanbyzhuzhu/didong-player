# TXT 逐句跟读测试

V0.0.18 保留原段落索引，在段内生成句子坐标，并按句提交系统 TTS / MultiTTS。
画面使用句子坐标显示当前句，不依赖 `onRangeStart`。暂停后保留画面，系统 TTS 继续时重读当前句，停止重开也从保存的句子开始。
既有的上一段/下一段、目录和书签继续按段定位。

## 纯 Java 测试

在项目根目录运行（JDK 17+）：

```powershell
javac -encoding UTF-8 -d build/reader-tests app/src/main/java/com/dongting/player/TtsText.java tools/tests/TtsTextTest.java
java -cp build/reader-tests com.dongting.player.TtsTextTest
```

覆盖中文标点、引号、小数、英文句子、空文本、旧段落坐标、长句和 emoji、前后句窗口及跨段显示。

## Android 服务与画面测试

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug
python tools/test_reader_device.py --adb C:\路径\到\adb.exe --serial emulator-5554
```

仅在可丢弃模拟器上运行。脚本安装正式代码的 debug APK 和测试 APK，临时修改 TTS 引擎与测试设置，结束时恢复原设置。
测试 APK 中的 `NoRangeTtsService` 每句生成 2.5 秒静音，故意不发字词位置回调；另由 Python 本地服务器模拟 MultiTTS，返回没有文本时间轴的 WAV。

检查实际朗读服务、首页和播放页的当前句；暂停/继续、停止后恢复、跳段后的旧回调隔离、段落索引不变，以及 MultiTTS 请求是否严格为单句。
截图和运行记录保存在 `build/reader-system.png`、`build/reader-multitts.png` 和 `build/reader-device-results.txt`。
测试用引擎只在 androidTest APK 中，主 APK 不包含该服务。

## 真机验收

安装主 APK，导入包含多句的 TXT，点击朗读，确认当前句加亮并随着声音切到下一句，前后句正常显示。
分别检查系统 TTS、暂停后继续、停止后重开、段落跳转和长句换行；有 MultiTTS 环境时再检查实际音色和连续合成速度。
模拟器的静音合成验证控制与同步逻辑，不替代真机语音引擎的音质、语调及网络合成延迟验收。
