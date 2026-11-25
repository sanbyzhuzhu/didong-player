# Changelog

## V0.0.20 - 2026-11-24

对外发布资料更新：2026-11-25，同步 GitHub/Gitee 源码、下载入口与本版宣发文案和配图。

- 完善 EPUB：从 container/OPF 读取入口，按 spine 的正文顺序提取内容，跳过非线性项目及独立导航正文，不再依赖 ZIP 文件排列顺序。
- 支持 EPUB 3 导航目录和 EPUB 2 NCX 目录，解析相对路径、百分号编码路径及章节锚点；目录缺失时回退到正文标题或正文文件列表。
- 首页和播放页的目录支持定位到同一 XHTML 文件内的具体小节，并从相应句子开始朗读。
- 新增 Markdown（.md/.markdown）、HTML（.html/.htm/.xhtml）、DOCX 的纯文字导入朗读；原有 TXT、JSON 继续支持，单文件导入与文件夹扫描同步更新。
- Markdown 清理常见格式标记并识别标题；HTML 提取正文、解码实体，排除 head、脚本、样式及隐藏节点；DOCX 提取段落、表格和超链接文字并识别标题样式，不朗读删除内容和域代码。
- 导入、播放页与朗读服务共用同一文本解析实现，沿用逐句高亮、暂停继续、进度和书签机制；旧 EPUB 进度与书签尽力按正文迁移并保留原始备份，无法匹配时给出提示。
- 未引入新的解析库、原生库或 OCR 模型，采用系统 XML/HTML 与 ZIP 能力；对损坏、加密、超限文档给出错误提示。
- 新增 EPUB 2/3、Markdown、HTML、DOCX 的样例测试与 Android 目录点击回归。Windows 浏览器版随包分发，此次多格式解析改动针对 Android 版。

## V0.0.19 - 2026-11-24

- 全新「静谧深海」极简苹果风 App 图标（全套 mdpi–xxxhdpi 与自适应圆角安全边距），状态栏小图标与锁屏默认封面同步统一。
- 弹窗与下拉框全面主题化：主题选择、播放速度、设置菜单升级为苹果风底部圆角抽屉，列表与人声下拉框跟随 5 套主题自适应变色。
- 大屏舞台新增「字号」一键切换（小字 / 标准 / 大字 / 特大），底部 4 入口升级为细线条矢量小图标，首页进度条在 TXT 朗读时自动联动为段落进度并支持拖动跳段。
- Windows 网页版同步升级为极简苹果风与 5 套主题切换（默认静谧深海）。

## V0.0.18 - 2026-11-24

- TXT 改为逐句朗读：保留原有段落索引，在段内按句号、问号、感叹号、分号及换行拆句，逐句提交系统 TTS 或 MultiTTS；不依赖引擎提供字词位置回调。
- 首页和播放页统一显示上一句、当前句、下一句；当前句整句高亮，自动保持可见，多行句子的后续行也会高亮。
- 暂停后保留当前句显示；系统 TTS 继续时从当前句开头重读，MultiTTS 正常暂停后继续原句音频，避免半句丢失。
- 记录句子在段内的位置，停止后重新开始可恢复到原句；段落目录、搜索与书签沿用原索引，三个界面共用分段算法。
- 为系统 TTS 回调增加唯一标识，跳段、停止或重启后忽略旧回调，避免误跳句；MultiTTS 回退系统 TTS 时只重读当前句。
- 增加 Android TTS 引擎的包可见性声明；新增纯 Java 分句测试与模拟器真实服务/界面回归测试，覆盖没有字词回调的语音引擎及本地模拟 MultiTTS。
- APK、源码及现有 Windows 浏览器版按统一发布目录打包；Windows 版此次仅随包分发，不包含 Android 逐句朗读改动。

## V0.0.17 - 2026-11-24

- 修复历史闪退日志阻止首页初始化、主题检查反复重建 Activity 导致的启动闪退循环。
- 初始化未完成时跳过主题重建与服务界面回调，退出时仅释放已创建的播放器，并移除旧监听器。
- 崩溃记录器在进程中只安装一次，使用 Application Context，避免反复重建后持有旧 Activity。
- 设置中增加“查看上次闪退日志”，支持复制或清除；保留诊断信息，但不再让旧日志阻止启动。
- 文件选择器不支持持久授权时继续使用临时读取权限，避免导入时抛出 SecurityException。
- 使用 AndroidX 返回事件分发器兼容 Android 16 返回手势，补充 Media3 实验 API 显式声明，通过 Lint 错误检查。
- 增加模拟器启动回归脚本 `tools/smoke_startup.py`，覆盖正常启动、历史日志恢复、后台返回及初始化失败后退出。

## V0.0.16 - 2026-11-04

播放稳定性与高频场景专项：

- 修复「播放一段时间后自动从头开始」：`STATE_ENDED` 不再无条件把记忆位置写成 0；恢复会话只重建一次队列并直接定位到记忆位置；AB 循环不再随文件自动启用，需手动点「AB循环」；外部打开的文件只处理一次，避免服务重连时重播。
- 修复「偶尔自己停止」：音频焦点改由服务统一管理，来电 / 其它应用占用音频时暂停；拔出耳机或蓝牙断连时暂停；以上暂停一律不自动恢复，必须手动点播放。
- 后台更稳：暂停时保持前台服务（不再降级被系统回收）、补 `WAKE_LOCK` 与电池优化设置入口、划掉通知不再误杀播放、播放错误不再静默卡死。
- 进度不丢：播放中每 5 秒自动落盘一次，服务被回收前兜底保存；「清除播放位置」同步清理 SQLite 镜像；睡眠定时不再跨启动自动恢复。
- 歌词/字幕：同名 LRC/SRT 自动匹配绑定；支持一行多个时间标签、WebVTT、SRT 内联标签、BOM/GBK；二分查找当前行，主界面显示上一行/当前行/下一行并高亮当前行；恢复上次播放时也会重新载入字幕。
- 后台朗读：摘耳机 / 来电同样暂停且不自动恢复；补唤醒锁与前台保活；修复通知栏「继续」后系统 TTS 只读一段就停、以及「从头读」在暂停态无效的问题。
- 界面：统一暖棕配色（系统栏与内容区不再割裂）、按钮圆角与按压反馈、主操作按钮强调色；抽出 `UiKit` 消除三个界面的重复控件代码，删除约 115 行不可达死代码。

## V0.0.15 - 2026-05-16

- Renamed the app to 地洞播放器.
- Updated Android versionCode to 15 and versionName to 0.0.15.
- Updated Windows prototype naming to DidongPlayer.
- Added open-source project documentation and Apache-2.0 license.
- Regenerated marketing assets for the new app name.

## V0.0.12

- Improved separation between TXT reading and normal media playback.
- Added reader progress controls and entries for chapters, bookmarks, search, previous paragraph and next paragraph.
- Updated the home visual area to follow the current reading position.
- Added system TTS `onRangeStart` capability detection.
- Improved background music management.
