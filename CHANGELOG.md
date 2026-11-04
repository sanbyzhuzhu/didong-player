# Changelog

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
