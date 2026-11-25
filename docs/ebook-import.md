# 电子书纯文字朗读（V0.0.20）

支持单文件或多文件导入，也可通过“书籍文件夹”扫描：

| 格式 | 提取方式与目录 |
| --- | --- |
| TXT | 原文读取及常见中文编码识别，沿用章节标题识别 |
| EPUB | 依据 OPF spine 阅读顺序；优先 EPUB 3 nav 目录，兼容 EPUB 2 NCX；支持文件内锚点 |
| Markdown（md、markdown） | 常见 Markdown 标记转为纯文字，标题作为目录；保留代码块的内容，不朗读围栏标记 |
| HTML（html、htm、xhtml） | 提取正文和标题，解码实体，过滤脚本、样式、head、隐藏节点；不访问网络资源 |
| DOCX | 段落、超链接及表格文字；依据段落标题样式/大纲级别生成目录，支持基于其他标题样式的自定义样式 |
| JSON | 沿用字段及文本值提取 |

入口为媒体库中的“导入电子书”“书籍文件夹”“电子书架”。开始朗读后，首页和播放页沿用逐句高亮。
点击“目录”中的项目会开始朗读相应章节；同一 EPUB 文件内有多个小节时，也会定位到小节所在的句子。

这是纯文字朗读，不还原电子书或 Word 的版式、图表、公式和分页。不支持 DRM、扫描件、图片识字、旧版 DOC、PDF、MOBI、AZW3。
Markdown 是轻量文字提取，非完整 CommonMark/GFM 排版引擎；复杂扩展语法可能仍有残余符号。

## EPUB 升级后的旧进度

旧版按 ZIP 项目顺序拼接正文，本版改按 spine 顺序，原段落数字不一定指向相同内容。
首次打开已有记录的 EPUB 时，会尝试按原句/原段文字匹配新正文，迁移进度与书签。
原值保存在 `legacyEpubChunk:`、`legacyEpubOffset:`、`legacyEpubMarks:` 对应的偏好设置中；无法匹配时从头开始，并在手动导入时给出提示。
文本重复时匹配可能不唯一，升级后建议核对一次当前句或使用目录重新定位。

## 容量与限制

未新增外部解析依赖。解压读取不写出书内资源，不下载远程内容；设有文件数量、单个文本部件及累计大小限制，超限时提示拆分导入。
纯文本最大读取 32 MiB；压缩包最多 10,000 项，单个正文/XML 部件最多 8 MiB，保留的正文与目录合计最多 32 MiB，全部解压数据最多 128 MiB。
图片会跳过存储，但仍计入解压数据限额。大型图片 EPUB 也可能达到限额。

## 测试

```powershell
javac -encoding UTF-8 -d build/book-tests app/src/main/java/com/dongting/player/ReadableBook.java app/src/main/java/com/dongting/player/BookTextParser.java app/src/main/java/com/dongting/player/TtsText.java app/src/main/java/com/dongting/player/BookProgress.java tools/tests/BookTextParserTest.java
java -cp build/book-tests com.dongting.player.BookTextParserTest
.\gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug
python tools/test_books_device.py --adb C:\路径\到\adb.exe --serial emulator-5554
```

Java 测试生成乱序 EPUB3、EPUB2、DOCX、Markdown、HTML 样例到 `build/book-fixtures/`。
Android 测试仅允许可丢弃模拟器，临时使用无字词回调的测试语音引擎，结束后恢复测试前偏好和系统 TTS 引擎。
测试检查实际导入、首页/播放页正文一致、目录点击后的具体句子、旧 EPUB 进度迁移。
截图与结果位于 `build/book-catalog.png`、`build/book-device-results.txt`。
