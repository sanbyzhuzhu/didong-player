package com.dongting.player;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;

public class SettingsActivity extends AppCompatActivity {
    private static final String PREFS = "dongting_player";

    private SharedPreferences prefs;
    private SharedPreferences updatePrefs;
    private View updateDot;
    private TextView updateTitle;
    private TextView updateStatus;
    private Button updateCheckButton;
    private final SharedPreferences.OnSharedPreferenceChangeListener updateListener = (preferences, key) ->
            runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) refreshUpdateEntry(); });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.init(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        updatePrefs = AppUpdates.preferences(this);
        setContentView(buildContent());
        UiKit.applyWindowTheme(this);
        updatePrefs.registerOnSharedPreferenceChangeListener(updateListener);
        AppUpdates.checkInBackground(getApplicationContext(), false);
        refreshUpdateEntry();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(UiKit.BG);
        UiKit.applySystemBarInsets(scroll, 0, 0);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(26));
        scroll.addView(root);

        TextView title = label("地洞设置中心", 22, UiKit.TEXT);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title);
        root.addView(label("外观主题、播放、朗读、视频与数据设置。", 13, UiKit.SUBTLE));

        // 新增：外观主题切换卡片（5 套苹果风主题，点击立即生效）
        View[] themeButtons = new View[UiKit.THEME_KEYS.length + 1];
        themeButtons[0] = label("当前主题：" + UiKit.currentThemeName() + "（点击下方立即切换）", 13, UiKit.SUBTLE);
        for (int i = 0; i < UiKit.THEME_KEYS.length; i++) {
            final String key = UiKit.THEME_KEYS[i];
            final String name = UiKit.THEME_NAMES[i];
            final String desc = UiKit.THEME_DESCS[i];
            boolean selected = key.equals(UiKit.CURRENT_THEME);
            Button b = btn((selected ? "✓ " : "") + name + " · " + desc, v -> {
                if (!key.equals(UiKit.CURRENT_THEME)) {
                    UiKit.setTheme(this, key);
                    toast("已切换主题：" + name);
                    recreate();
                }
            });
            if (selected) UiKit.makePrimary(this, b);
            themeButtons[i + 1] = b;
        }
        addSection(root, "外观主题", themeButtons);

        addSection(root, "播放设置",
                btn("恢复全部默认", v -> confirmResetAllDefaults()),
                btn("关闭睡眠定时", v -> {
                    prefs.edit().remove("sleepAt").putBoolean("stopAfterCurrent", false).putBoolean("stopAfterList", false).apply();
                    toast("已关闭睡眠定时");
                }),
                btn("清除当前播放记忆", v -> {
                    prefs.edit().remove("currentUri").remove("currentIndex").apply();
                    toast("已清除当前播放记忆");
                }),
                btn("后台播放保护", v -> openBatteryOptimizationSettings()),
                label("摘掉耳机、来电或其它应用占用音频时会自动暂停，需手动点播放才继续；播放进度每 5 秒自动保存。若后台仍被系统杀掉，请把本应用设为「不优化电池」。", 13, UiKit.SUBTLE));

        addSection(root, "音效设置",
                slider("播放音量", "volume", 100, 100),
                slider("无损增益", "boost", 3000, 0),
                slider("低音增强", "bass", 1000, 0),
                slider("立体感增强", "stereo", 1000, 0),
                label("主界面「音效」抽屉中还提供五段均衡器与 6 套预设，部分手机或蓝牙设备可能限制系统音效。", 13, UiKit.SUBTLE),
                btn("恢复音效默认", v -> {
                    prefs.edit().putInt("boost", 0).putInt("bass", 0).putInt("stereo", 0).putInt("volume", 100).apply();
                    toast("音效已恢复默认");
                }),
                btn("打开系统声音设置", v -> openSoundSettings()));

        addSection(root, "朗读设置",
                slider("朗读语速", "ttsRate", 150, 75),
                slider("朗读音调", "ttsPitch", 100, 50),
                slider("背景音乐音量", "bgVolume", 100, 25),
                btn("恢复朗读默认", v -> {
                    prefs.edit().putInt("ttsRate", 75).putInt("ttsPitch", 50).putInt("bgVolume", 25).remove("ttsVoice").apply();
                    notifyBackgroundVolumeIfNeeded("bgVolume");
                    toast("朗读设置已恢复默认");
                }),
                btn("清除地洞人声绑定", v -> {
                    prefs.edit().remove("ttsVoice").apply();
                    toast("已清除人声绑定，朗读将使用系统默认 TTS 引擎");
                }),
                btn("使用系统TTS", v -> {
                    prefs.edit().putString("ttsProvider", "system").apply();
                    stopService(new Intent(this, TextReaderService.class));
                    toast("已切换为系统 TTS，现有朗读功能保持不变");
                }),
                btn("使用MultiTTS接口", v -> {
                    prefs.edit()
                            .putString("ttsProvider", "multitts")
                            .putString("externalTtsForwardUrl", prefs.getString("externalTtsForwardUrl", "http://127.0.0.1:8774/forward"))
                            .apply();
                    stopService(new Intent(this, TextReaderService.class));
                    toast("已切换为 MultiTTS 接口，下次朗读会使用 MultiTTS");
                }),
                btn("设置MultiTTS音色ID", v -> showExternalTtsVoiceDialog()),
                btn("设置MultiTTS地址", v -> showExternalTtsUrlDialog()),
                btn("打开MultiTTS", v -> openMultiTtsApp()),
                btn("测试MultiTTS接口", v -> testExternalTtsEndpoint()),
                slider("MultiTTS音量", "externalTtsVolume", 100, 100),
                btn("停止后台朗读", v -> {
                    stopService(new Intent(this, TextReaderService.class));
                    toast("已停止后台朗读");
                }),
                btn("清除 TXT 记忆", v -> confirmClearTextMemory()));

        addSection(root, "视频设置",
                check("视频播放时保持屏幕常亮", "videoKeepScreenOn", true),
                check("视频全屏播放时自动隐藏控制台", "videoFullscreenControls", true),
                check("视频全屏时填充画面", "videoFillScreen", false),
                label("默认完整显示原比例，适合手机竖屏视频；开启填充画面会铺满屏幕但可能裁掉边缘。视频控制台支持导入 SRT 字幕、同名字幕自动匹配、字幕偏移和旋转画面。", 13, UiKit.SUBTLE));

        addSection(root, "数据管理",
                btn("查看上次闪退日志", v -> showLastCrash()),
                btn("清空最近播放", v -> {
                    clearPlaylist("最近播放");
                    toast("已清空最近播放");
                }),
                btn("清空收藏", v -> confirmClearPlaylist("收藏")),
                btn("清除所有播放位置", v -> confirmClearPrefix("pos:", "播放位置")),
                btn("清除所有 AB 循环", v -> confirmClearPrefix("ab:", "AB 循环")),
                btn("清除所有书签", v -> confirmClearPrefix("bookmarks:", "书签")),
                label("清空列表只会删除 App 内记录，不会删除手机里的音频/视频文件。", 13, UiKit.SUBTLE));

        addSection(root, "开发说明",
                label("地洞播放器由朱振坚个人利用 AI 在业余时间开发，并按 Apache-2.0 许可证开源发布。", 14, UiKit.TEXT),
                label("首发在公众号：小二菜园", 14, UiKit.SUBTLE),
                label("微信和 QQ：254850837", 14, UiKit.SUBTLE),
                label("祝大家学习愉快！", 14, UiKit.ACCENT));

        Button backBtn = btn("返回播放器", v -> finish());
        UiKit.makePrimary(this, backBtn);
        root.addView(backBtn, fullParams());
        addUpdateSection(root);
        return scroll;
    }

    private void addUpdateSection(LinearLayout root) {
        LinearLayout entry = new LinearLayout(this);
        entry.setTag("app-update-entry");
        entry.setOrientation(LinearLayout.HORIZONTAL);
        entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setPadding(dp(4), dp(12), dp(4), dp(12));
        updateTitle = label("软件更新", 16, UiKit.TEXT);
        updateTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        entry.addView(updateTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        updateDot = new View(this);
        updateDot.setTag("app-update-dot");
        updateDot.setContentDescription("有新版本可更新");
        updateDot.setBackground(UiKit.roundRect(this, 0xFFFF453A, android.graphics.Color.TRANSPARENT, 5, 0));
        entry.addView(updateDot, new LinearLayout.LayoutParams(dp(9), dp(9)));
        entry.setOnClickListener(v -> showUpdateDetails());
        updateStatus = label("尚未检查更新", 13, UiKit.SUBTLE);
        updateStatus.setTag("app-update-status");
        updateCheckButton = btn("检查更新", v -> {
            AppUpdates.checkInBackground(getApplicationContext(), true);
            refreshUpdateEntry();
        });
        updateCheckButton.setTag("app-update-check");
        addSection(root, "软件更新", entry,
                label("当前版本：V" + AppUpdates.installedName(this), 13, UiKit.SUBTLE), updateStatus,
                updateCheckButton, label("只在这里用红点提示。不弹窗、不自动下载或安装，忽略后可继续使用当前版本。", 12, UiKit.SUBTLE));
    }

    private void refreshUpdateEntry() {
        if (updateStatus == null) return;
        UpdateInfo info = AppUpdates.cached(this);
        boolean newer = info != null && info.versionCode > AppUpdates.installedCode(this);
        boolean ignored = newer && info.versionCode <= updatePrefs.getLong(AppUpdates.IGNORED, 0);
        updateDot.setVisibility(AppUpdates.showDot(this) ? View.VISIBLE : View.GONE);
        updateTitle.setText(newer ? (ignored ? "软件更新 · 已忽略 V" : "软件更新 · 新版本 V") + info.versionName : "软件更新");
        boolean checking = AppUpdates.isChecking();
        updateCheckButton.setEnabled(!checking);
        if (checking) {
            updateStatus.setText("正在后台检查，不影响播放和朗读…");
        } else if (updatePrefs.getLong(AppUpdates.FINISHED, 0) == 0 && info == null) {
            updateStatus.setText("尚未检查更新，可点击“检查更新”。");
        } else if (!updatePrefs.getBoolean(AppUpdates.SUCCEEDED, false)) {
            updateStatus.setText(info == null ? "暂时无法连接更新源，仍可正常使用当前版本。"
                    : "网络暂不可用，显示上次的检查结果；当前版本可继续使用。");
        } else if (newer) {
            updateStatus.setText(ignored ? "此版本已忽略，不再显示红点；更高版本仍可提示。点击条目仍可查看。"
                    : "发现新版本。点击上方条目查看，是否更新由你决定。");
        } else {
            updateStatus.setText("当前已是最新版本（检查来源：" + updatePrefs.getString(AppUpdates.SOURCE, "GitHub / Gitee") + "）。");
        }
    }

    private void showUpdateDetails() {
        UpdateInfo info = AppUpdates.cached(this);
        if (info == null || info.versionCode <= AppUpdates.installedCode(this)) {
            AppUpdates.checkInBackground(getApplicationContext(), true);
            refreshUpdateEntry();
            return;
        }
        boolean ignored = info.versionCode <= updatePrefs.getLong(AppUpdates.IGNORED, 0);
        new AlertDialog.Builder(this)
                .setTitle((ignored ? "已忽略的版本 V" : "发现新版本 V") + info.versionName)
                .setMessage("当前版本 V" + AppUpdates.installedName(this) + "\n\n" + info.notes
                        + "\n\n不会自动下载或安装。选择更新后还需确认；忽略或关闭都不影响当前版本使用。")
                .setPositiveButton("更新", (dialog, which) -> confirmUpdate(info))
                .setNeutralButton("忽略此版本", (dialog, which) -> {
                    AppUpdates.ignore(this, info);
                    refreshUpdateEntry();
                })
                .setNegativeButton("暂不更新", null).show();
    }

    private void confirmUpdate(UpdateInfo info) {
        new AlertDialog.Builder(this).setTitle("确认前往官方发布页？")
                .setMessage("将打开 GitHub V" + info.versionName + " 官方发布页，由你选择下载和安装。\n"
                        + "此操作不会替换当前版本，也不会清空数据。若不更新，可取消并继续使用。\n\n"
                        + "Gitee 提供同步源码及说明，安装包以 GitHub 发布页为准。")
                .setPositiveButton("确认前往下载", (dialog, which) -> openUpdatePage(info.releaseUrl))
                .setNeutralButton("查看 Gitee 说明", (dialog, which) -> openUpdatePage(info.mirrorUrl))
                .setNegativeButton("取消", null).show();
    }

    private void openUpdatePage(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
        catch (RuntimeException error) { updateStatus.setText("无法打开浏览器，请稍后从官方仓库的发布页下载。"); }
    }

    @Override protected void onResume() {
        super.onResume();
        refreshUpdateEntry();
    }

    @Override protected void onDestroy() {
        if (updatePrefs != null) updatePrefs.unregisterOnSharedPreferenceChangeListener(updateListener);
        super.onDestroy();
    }

    private void showLastCrash() {
        String trace = prefs.getString("lastCrashTrace", "");
        if (trace == null || trace.trim().isEmpty()) {
            toast("没有记录到闪退日志");
            return;
        }
        TextView content = label(trace, 12, UiKit.TEXT);
        content.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        new AlertDialog.Builder(this)
                .setTitle("上次闪退日志")
                .setView(scroll)
                .setPositiveButton("复制", (dialog, which) -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("crash", trace));
                        toast("已复制错误堆栈");
                    }
                })
                .setNeutralButton("清除日志", (dialog, which) ->
                        prefs.edit().remove("lastCrashTrace").apply())
                .setNegativeButton("关闭", null)
                .show();
    }

    private void addSection(LinearLayout root, String title, View... views) {
        root.addView(UiKit.sectionTitle(this, title));
        // 每个分区包一张卡片，视觉上与其他界面统一
        LinearLayout card = UiKit.card(this, 12);
        for (View view : views) card.addView(view, fullParams());
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, dp(2), 0, dp(8));
        root.addView(card, cardParams);
    }

    private View slider(String title, String key, int max, int defaultValue) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(4), 0, dp(8));
        TextView value = label(title + "：" + prefs.getInt(key, defaultValue), 14, UiKit.SUBTLE);
        SeekBar bar = UiKit.seekBar(this);
        bar.setMax(max);
        bar.setProgress(prefs.getInt(key, defaultValue));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(title + "：" + progress);
                if (fromUser) {
                    prefs.edit().putInt(key, progress).apply();
                    notifyBackgroundVolumeIfNeeded(key);
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.edit().putInt(key, seekBar.getProgress()).apply();
                notifyBackgroundVolumeIfNeeded(key);
            }
        });
        box.addView(value);
        box.addView(bar);
        return box;
    }

    private void notifyBackgroundVolumeIfNeeded(String key) {
        if (!"bgVolume".equals(key) || !TextReaderService.isRunning()) return;
        startService(new Intent(this, TextReaderService.class).setAction(TextReaderService.ACTION_BACKGROUND_UPDATE));
    }

    private View check(String text, String key, boolean defaultValue) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setTextColor(UiKit.TEXT);
        box.setButtonTintList(android.content.res.ColorStateList.valueOf(UiKit.ACCENT));
        box.setChecked(prefs.getBoolean(key, defaultValue));
        box.setOnCheckedChangeListener((buttonView, isChecked) -> prefs.edit().putBoolean(key, isChecked).apply());
        return box;
    }

    private void confirmClearTextMemory() {
        new AlertDialog.Builder(this)
                .setTitle("清除 TXT 记忆")
                .setMessage("会清除上次导入文本和朗读背景音乐记录，已导入文件本身不会被删除。")
                .setPositiveButton("清除", (dialog, which) -> {
                    prefs.edit().remove("lastTextUri").remove("ttsBgUri").apply();
                    toast("TXT 记忆已清除");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showExternalTtsVoiceDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(prefs.getString("externalTtsVoice", ""));
        input.setHint("例如 MultiTTS voices 接口里的 voice id，可留空");
        input.setTextColor(UiKit.TEXT);
        input.setHintTextColor(UiKit.SUBTLE);
        input.setBackground(UiKit.inputBg(this));
        input.setPadding(dp(10), dp(8), dp(10), dp(8));
        new AlertDialog.Builder(this)
                .setTitle("MultiTTS 音色 ID")
                .setMessage("如果 MultiTTS 的 /voices 能看到 voiceCode 或 voice id，可填在这里。留空则使用 MultiTTS 当前默认音色。")
                .setView(input)
                .setPositiveButton("保存", (dialog, which) -> {
                    String value = input.getText() == null ? "" : input.getText().toString().trim();
                    SharedPreferences.Editor editor = prefs.edit();
                    if (value.isEmpty()) editor.remove("externalTtsVoice"); else editor.putString("externalTtsVoice", value);
                    editor.apply();
                    toast(value.isEmpty() ? "已使用 MultiTTS 默认音色" : "已保存 MultiTTS 音色：" + value);
                })
                .setNeutralButton("清空", (dialog, which) -> {
                    prefs.edit().remove("externalTtsVoice").apply();
                    toast("已清空 MultiTTS 音色 ID");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showExternalTtsUrlDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(prefs.getString("externalTtsForwardUrl", "http://127.0.0.1:8774/forward"));
        input.setHint("http://127.0.0.1:8774/forward");
        input.setTextColor(UiKit.TEXT);
        input.setHintTextColor(UiKit.SUBTLE);
        input.setBackground(UiKit.inputBg(this));
        input.setPadding(dp(10), dp(8), dp(10), dp(8));
        new AlertDialog.Builder(this)
                .setTitle("MultiTTS 转发地址")
                .setMessage("如果 MultiTTS 实际端口不是 8774，可在这里改。必须包含 /forward。")
                .setView(input)
                .setPositiveButton("保存", (dialog, which) -> {
                    String value = input.getText() == null ? "" : input.getText().toString().trim();
                    if (value.isEmpty()) value = "http://127.0.0.1:8774/forward";
                    prefs.edit().putString("externalTtsForwardUrl", value).apply();
                    toast("已保存 MultiTTS 地址");
                })
                .setNeutralButton("恢复默认", (dialog, which) -> {
                    prefs.edit().putString("externalTtsForwardUrl", "http://127.0.0.1:8774/forward").apply();
                    toast("已恢复默认地址");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openMultiTtsApp() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("org.nobody.multitts");
        if (intent == null) {
            toast("未找到 MultiTTS：org.nobody.multitts");
            return;
        }
        startActivity(intent);
    }

    private void testExternalTtsEndpoint() {
        toast("正在测试 MultiTTS 接口");
        new Thread(() -> {
            String result = runExternalTtsTest();
            new Handler(Looper.getMainLooper()).post(() -> new AlertDialog.Builder(this)
                    .setTitle("MultiTTS 接口测试")
                    .setMessage(result)
                    .setPositiveButton("确定", null)
                    .show());
        }).start();
    }

    private String runExternalTtsTest() {
        String forward = prefs.getString("externalTtsForwardUrl", "http://127.0.0.1:8774/forward");
        if (forward == null || forward.trim().isEmpty()) forward = "http://127.0.0.1:8774/forward";
        String voices = forward.replace("/forward", "/voices");
        StringBuilder message = new StringBuilder();
        message.append("提供方：").append(prefs.getString("ttsProvider", "system")).append('\n');
        message.append("forward：").append(forward).append('\n');
        message.append("voice：").append(prefs.getString("externalTtsVoice", "默认")).append('\n');
        message.append("上次状态：").append(prefs.getString("lastExternalTtsState", "无")).append('\n');
        message.append("上次错误：").append(prefs.getString("lastExternalTtsError", "无")).append('\n');
        message.append("上次信息：").append(prefs.getString("lastExternalTtsInfo", "无")).append("\n\n");
        message.append("voices：").append(testHttp(voices, false)).append("\n\n");
        try {
            String separator = forward.contains("?") ? "&" : "?";
            String url = forward + separator
                    + "text=" + URLEncoder.encode("地洞 MultiTTS 接口测试。", "UTF-8")
                    + "&speed=50&volume=100&pitch=50";
            String voice = prefs.getString("externalTtsVoice", "");
            if (!voice.trim().isEmpty()) url += "&voice=" + URLEncoder.encode(voice.trim(), "UTF-8");
            message.append("forward测试：").append(testHttp(url, true));
        } catch (Exception ex) {
            message.append("forward测试异常：").append(ex.getClass().getSimpleName()).append(": ").append(ex.getMessage());
        }
        return message.toString();
    }

    private String testHttp(String rawUrl, boolean binary) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(rawUrl).openConnection();
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(6000);
            connection.setRequestMethod("GET");
            int code = connection.getResponseCode();
            String type = connection.getContentType();
            InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            int total = 0;
            StringBuilder preview = new StringBuilder();
            byte[] buffer = new byte[1024];
            int read;
            while (input != null && (read = input.read(buffer)) != -1 && total < 8192) {
                if (!binary && preview.length() < 600) preview.append(new String(buffer, 0, read));
                total += read;
            }
            return "HTTP " + code + "，类型 " + type + "，读取 " + total + " 字节"
                    + (preview.length() > 0 ? "\n" + preview : "");
        } catch (Exception ex) {
            return ex.getClass().getSimpleName() + ": " + ex.getMessage();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void confirmResetAllDefaults() {
        new AlertDialog.Builder(this)
                .setTitle("恢复全部默认")
                .setMessage("会恢复播放、音效、朗读和视频设置，不会删除列表和源文件。")
                .setPositiveButton("恢复", (dialog, which) -> {
                    prefs.edit()
                            .putInt("volume", 100)
                            .putInt("boost", 0)
                            .putInt("bass", 0)
                            .putInt("stereo", 0)
                            .putInt("ttsRate", 75)
                            .putInt("ttsPitch", 50)
                            .putInt("bgVolume", 25)
                            .putBoolean("videoKeepScreenOn", true)
                            .putBoolean("videoFullscreenControls", true)
                            .putBoolean("videoFillScreen", false)
                            .putFloat("lastSpeed", 1f)
                            .remove("ttsVoice")
                            .remove("sleepAt")
                            .putBoolean("stopAfterCurrent", false)
                            .putBoolean("stopAfterList", false)
                            .apply();
                    notifyBackgroundVolumeIfNeeded("bgVolume");
                    toast("已恢复全部默认，返回播放器后生效");
                    recreate();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmClearPlaylist(String name) {
        new AlertDialog.Builder(this)
                .setTitle("清空" + name)
                .setMessage("只清空 App 内列表，不删除源文件。")
                .setPositiveButton("清空", (dialog, which) -> {
                    clearPlaylist(name);
                    toast("已清空" + name);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmClearPrefix(String prefix, String label) {
        new AlertDialog.Builder(this)
                .setTitle("清除所有" + label)
                .setMessage("只清除 App 内记忆，不会删除源文件。")
                .setPositiveButton("清除", (dialog, which) -> {
                    int count = clearKeysWithPrefix(prefix);
                    toast("已清除 " + count + " 条" + label + "记录");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int clearKeysWithPrefix(String prefix) {
        int count = PlaylistStore.clearKeysWithPrefix(prefs, prefix);
        // prefs 与 SQLite 镜像要同步清，否则读回时旧值会"复活"
        DongtingDatabase db = new DongtingDatabase(this);
        try {
            db.removePrefix(prefix);
        } catch (RuntimeException ignored) {
        } finally {
            db.close();
        }
        return count;
    }

    private void clearPlaylist(String name) {
        PlaylistStore.clearPlaylist(prefs, name);
    }

    private void openSoundSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SOUND_SETTINGS));
        } catch (Exception ignored) {
        }
    }

    private void openBatteryOptimizationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            toast("请把地洞播放器设为「不优化」，后台听书更稳定");
        } catch (Exception ignored) {
            toast("当前系统没有提供电池优化设置入口");
        }
    }

    private Button btn(String text, View.OnClickListener listener) {
        return UiKit.button(this, text, listener);
    }

    private TextView label(String text, int sp, int color) {
        return UiKit.label(this, text, sp, color);
    }

    private LinearLayout.LayoutParams fullParams() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return UiKit.dp(this, value);
    }
}
