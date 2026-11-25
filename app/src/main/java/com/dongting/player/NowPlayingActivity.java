package com.dongting.player;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.ViewGroup;
import android.graphics.Typeface;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.exoplayer.ExoPlayer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class NowPlayingActivity extends AppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PlaybackService playbackService;
    private ExoPlayer player;
    private TextView title;
    private TextView subtitle;
    private TextView time;
    private TextView speed;
    private TextView mode;
    private DongtingVisualView visual;
    private SeekBar seekBar;
    private ImageView playButton;
    private LinearLayout mediaChipRow;
    private LinearLayout readingChipRow1;
    private LinearLayout readingChipRow2;
    private SharedPreferences prefs;
    private boolean dragging;
    private String appliedTheme = "";
    private String cachedTextUri = "";
    private List<String> cachedTextChunks = new ArrayList<>();

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            playbackService = ((PlaybackService.LocalBinder) service).getService();
            player = playbackService.getPlayer();
            updateUi();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            player = null;
            playbackService = null;
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appliedTheme = UiKit.init(this);
        prefs = getSharedPreferences("dongting_player", MODE_PRIVATE);
        LinearLayout content = buildContent();
        UiKit.applySystemBarInsets(content, dp(16), dp(12));
        setContentView(content);
        UiKit.applyWindowTheme(this);
        bindService(new Intent(this, PlaybackService.class), connection, Context.BIND_AUTO_CREATE);
        handler.post(ticker);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!UiKit.init(this).equals(appliedTheme)) {
            recreate();
        }
    }

    private LinearLayout buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(14));
        root.setBackgroundColor(UiKit.BG);

        // 极简顶栏：左返回 + 中间标题 + 右主题切换
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        Button backChip = chip("返回", v -> finish());
        topBar.addView(backChip);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(10), 0, dp(10), 0);
        title = label("地洞播放器", 17, UiKit.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER);
        subtitle = label("正在播放", 12, UiKit.SUBTLE);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subtitle.setGravity(Gravity.CENTER);
        titleBox.addView(title);
        titleBox.addView(subtitle);
        topBar.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button fontChip = chip("字号", v -> {
            String name = UiKit.cycleStageFont(this);
            if (visual != null) visual.invalidate();
            android.widget.Toast.makeText(this, "舞台字号：" + name, android.widget.Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams fontLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fontLp.setMargins(0, 0, dp(6), 0);
        topBar.addView(fontChip, fontLp);
        topBar.addView(chip("主题", v -> showThemePickerDialog()));
        root.addView(topBar);

        // 大屏沉浸歌词 / 朗读舞台
        visual = new DongtingVisualView(this);
        visual.setState("地洞播放器", "正在播放", "耳朵在树洞里听见了声音", false, false, 1f);
        LinearLayout.LayoutParams visualParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        visualParams.setMargins(0, dp(12), 0, dp(12));
        root.addView(visual, visualParams);

        time = label("00:00 / 00:00", 13, UiKit.SUBTLE);
        time.setGravity(Gravity.CENTER);
        root.addView(time);

        seekBar = UiKit.seekBar(this);
        seekBar.setMax(1000);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && isReadingMode()) {
                    time.setText(readingProgressTextForProgress(progress));
                    return;
                }
                if (fromUser && player != null && player.getDuration() > 0 && player.getDuration() != C.TIME_UNSET) {
                    long target = player.getDuration() * progress / 1000L;
                    time.setText(MediaUtils.formatMs(target) + " / " + MediaUtils.formatMs(player.getDuration()));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (isReadingMode()) {
                    seekReadingByProgress(bar.getProgress());
                    dragging = false;
                    return;
                }
                if (player != null && player.getDuration() > 0 && player.getDuration() != C.TIME_UNSET) {
                    player.seekTo(player.getDuration() * bar.getProgress() / 1000L);
                    savePosition();
                }
                dragging = false;
            }
        });
        root.addView(seekBar);
        playButton = UiKit.roundPrimaryButton(this, R.drawable.ic_play, v -> {
            if (isReadingMode()) {
                sendTextReaderAction(TextReaderService.ACTION_TOGGLE);
                updateUi();
                return;
            }
            if (player == null) return;
            if (player.isPlaying()) {
                player.pause();
                savePosition();
            } else {
                // 用户显式点播放才继续，清掉"摘耳机/来电"的暂停标记
                prefs.edit().remove("lastPauseReason").apply();
                player.play();
            }
            updateUi();
        });
        speed = label("", 12, UiKit.MUTED);
        mode = label("", 12, UiKit.MUTED);

        // 主控排：上一首 / 大圆播放钮 / 下一首
        LinearLayout transport = new LinearLayout(this);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        transport.setGravity(Gravity.CENTER);
        ImageView prevButton = UiKit.iconButton(this, R.drawable.ic_prev, UiKit.TEXT, v -> {
            if (isReadingMode()) {
                sendTextReaderAction(TextReaderService.ACTION_PREVIOUS);
            } else if (player != null && player.hasPreviousMediaItem()) {
                player.seekToPreviousMediaItem();
            }
            updateUi();
        });
        LinearLayout.LayoutParams prevParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        prevParams.setMargins(dp(12), dp(6), dp(12), dp(6));
        transport.addView(prevButton, prevParams);
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(74), dp(74));
        playParams.setMargins(dp(20), dp(4), dp(20), dp(4));
        transport.addView(playButton, playParams);
        ImageView nextButton = UiKit.iconButton(this, R.drawable.ic_next, UiKit.TEXT, v -> {
            if (isReadingMode()) {
                sendTextReaderAction(TextReaderService.ACTION_NEXT);
            } else if (player != null && player.hasNextMediaItem()) {
                player.seekToNextMediaItem();
            }
            updateUi();
        });
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        nextParams.setMargins(dp(12), dp(6), dp(12), dp(6));
        transport.addView(nextButton, nextParams);
        root.addView(transport);

        // 按模式智能显示：影音模式只显示影音控制，朗读模式只显示朗读控制
        mediaChipRow = row(
                chip("-15s", v -> seekBy(-15000)),
                chip("+30s", v -> seekBy(30000)),
                chip("减速", v -> changeSpeed(-0.25f)),
                chip("加速", v -> changeSpeed(0.25f)),
                chip("循环", v -> cycleRepeatMode())
        );
        root.addView(mediaChipRow);

        readingChipRow1 = row(
                chip("目录", v -> showReadingCatalogDialog()),
                chip("书签", v -> showReadingBookmarksDialog()),
                chip("加书签", v -> addReadingBookmark()),
                chip("搜索", v -> showReadingSearchDialog())
        );
        root.addView(readingChipRow1);

        readingChipRow2 = row(
                chip("上一段", v -> seekReadingByDelta(-1)),
                chip("下一段", v -> seekReadingByDelta(1))
        );
        root.addView(readingChipRow2);

        // 底部状态行：左速度、右循环模式
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setPadding(dp(2), dp(6), dp(2), dp(2));
        statusRow.addView(speed, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mode.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        statusRow.addView(mode, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(statusRow);
        return root;
    }

    private void showThemePickerDialog() {
        String[] labels = new String[UiKit.THEME_KEYS.length];
        int checked = 0;
        for (int i = 0; i < UiKit.THEME_KEYS.length; i++) {
            if (UiKit.THEME_KEYS[i].equals(UiKit.CURRENT_THEME)) checked = i;
            labels[i] = UiKit.THEME_NAMES[i] + "  ·  " + UiKit.THEME_DESCS[i];
        }
        UiKit.showChoiceSheet(this, "选择外观主题", labels, checked, which -> {
            String chosen = UiKit.THEME_KEYS[which];
            if (!chosen.equals(UiKit.CURRENT_THEME)) {
                UiKit.setTheme(this, chosen);
                recreate();
            }
        });
    }

    private void seekBy(long delta) {
        if (isReadingMode()) {
            seekReadingByDelta(delta < 0 ? -1 : 1);
            return;
        }
        if (player == null) return;
        long duration = player.getDuration();
        long target = Math.max(0, player.getCurrentPosition() + delta);
        if (duration > 0 && duration != C.TIME_UNSET) target = Math.min(duration, target);
        player.seekTo(target);
        savePosition();
        updateUi();
    }

    private void changeSpeed(float delta) {
        if (isReadingMode()) return;
        if (player == null) return;
        float next = Math.max(0.25f, Math.min(8f, player.getPlaybackParameters().speed + delta));
        player.setPlaybackParameters(new PlaybackParameters(next, 1f));
        prefs.edit().putFloat("lastSpeed", next).apply();
        updateUi();
    }

    private void cycleRepeatMode() {
        if (isReadingMode()) return;
        if (player == null) return;
        if (player.getRepeatMode() == Player.REPEAT_MODE_OFF) {
            player.setRepeatMode(Player.REPEAT_MODE_ONE);
        } else if (player.getRepeatMode() == Player.REPEAT_MODE_ONE) {
            player.setRepeatMode(Player.REPEAT_MODE_ALL);
        } else {
            player.setRepeatMode(Player.REPEAT_MODE_OFF);
        }
        updateUi();
    }

    private void savePosition() {
        if (player == null || prefs == null || player.getCurrentMediaItem() == null) return;
        MediaItem item = player.getCurrentMediaItem();
        if (item.localConfiguration == null) return;
        Uri uri = item.localConfiguration.uri;
        prefs.edit()
                .putString("currentUri", uri.toString())
                .putLong("pos:" + uri, player.getCurrentPosition())
                .putInt("currentIndex", player.getCurrentMediaItemIndex())
                .apply();
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateUi();
            handler.postDelayed(this, 500);
        }
    };

    private void updateUi() {
        boolean reading = isReadingMode();
        if (mediaChipRow != null) mediaChipRow.setVisibility(reading ? android.view.View.GONE : android.view.View.VISIBLE);
        if (readingChipRow1 != null) readingChipRow1.setVisibility(reading ? android.view.View.VISIBLE : android.view.View.GONE);
        if (readingChipRow2 != null) readingChipRow2.setVisibility(reading ? android.view.View.VISIBLE : android.view.View.GONE);
        if (reading) {
            updateReadingUi();
            return;
        }
        if (player == null) return;
        if (player.getCurrentMediaItem() != null) {
            CharSequence t = player.getCurrentMediaItem().mediaMetadata.title;
            CharSequence s = player.getCurrentMediaItem().mediaMetadata.artist;
            title.setText(t == null ? "地洞播放器" : t);
            subtitle.setText(s == null ? "" : s);
        }
        long duration = player.getDuration();
        long position = player.getCurrentPosition();
        time.setText(MediaUtils.formatMs(position) + " / " + MediaUtils.formatMs(duration));
        if (!dragging && duration > 0 && duration != C.TIME_UNSET) seekBar.setProgress((int) Math.max(0, Math.min(1000, position * 1000L / duration)));
        playButton.setImageResource(player.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        playButton.setContentDescription(player.isPlaying() ? "暂停" : "播放");
        speed.setText(String.format(Locale.CHINA, "播放速度 %.2fx", player.getPlaybackParameters().speed));
        String repeat = player.getRepeatMode() == Player.REPEAT_MODE_ONE ? "单曲循环"
                : player.getRepeatMode() == Player.REPEAT_MODE_ALL ? "列表循环" : "顺序播放";
        mode.setText(repeat);
        if (visual != null) {
            visual.setState(String.valueOf(title.getText()), String.valueOf(subtitle.getText()),
                    nowPlayingBody(position), player.isPlaying(), false,
                    Math.max(0.6f, player.getPlaybackParameters().speed));
        }
    }

    private boolean isReadingMode() {
        return TextReaderService.isRunning() && (player == null || !player.isPlaying());
    }

    private void updateReadingUi() {
        String uri = prefs.getString("lastTextUri", "");
        String name = uri.isEmpty() ? "电子书朗读" : displayName(Uri.parse(uri));
        title.setText(name);
        subtitle.setText(TextReaderService.isPaused() ? "电子书朗读 · 已暂停" : "电子书朗读 · 正在朗读");
        time.setText(readingProgressText(uri));
        if (!dragging) seekBar.setProgress(readingProgressValue(uri));
        playButton.setImageResource(TextReaderService.isPaused() ? R.drawable.ic_play : R.drawable.ic_pause);
        playButton.setContentDescription(TextReaderService.isPaused() ? "继续" : "暂停");
        speed.setText("朗读由系统 TTS 控制");
        mode.setText("上一首/下一首 = 上一段/下一段");
        if (visual != null) {
            visual.setState(String.valueOf(title.getText()), String.valueOf(subtitle.getText()),
                    currentReadingChunk(), false, true, 1f);
        }
    }

    private List<String> getCachedTextChunks(String uri) {
        if (uri == null || uri.isEmpty()) return new ArrayList<>();
        if (!uri.equals(cachedTextUri) || cachedTextChunks.isEmpty()) {
            cachedTextUri = uri;
            String text = readText(Uri.parse(uri));
            cachedTextChunks = text.trim().isEmpty() ? new ArrayList<>() : splitTextForTts(text);
        }
        return cachedTextChunks;
    }

    private String readingProgressText(String uri) {
        if (uri == null || uri.isEmpty()) return "电子书朗读";
        List<String> chunks = getCachedTextChunks(uri);
        if (chunks.isEmpty()) return "电子书朗读";
        int index = Math.max(0, Math.min(prefs.getInt("ttsChunk:" + uri, 0), chunks.size() - 1));
        return "第 " + (index + 1) + " / " + chunks.size() + " 段";
    }

    private String readingProgressTextForProgress(int progress) {
        String uri = prefs.getString("lastTextUri", "");
        if (uri == null || uri.isEmpty()) return "电子书朗读";
        List<String> chunks = getCachedTextChunks(uri);
        if (chunks.isEmpty()) return "电子书朗读";
        int index = Math.max(0, Math.min(chunks.size() - 1, progress * chunks.size() / 1001));
        return "第 " + (index + 1) + " / " + chunks.size() + " 段";
    }

    private int readingProgressValue(String uri) {
        if (uri == null || uri.isEmpty()) return 0;
        List<String> chunks = getCachedTextChunks(uri);
        if (chunks.isEmpty()) return 0;
        int index = Math.max(0, Math.min(prefs.getInt("ttsChunk:" + uri, 0), chunks.size() - 1));
        return (int) Math.max(0, Math.min(1000, (index * 1000L) / Math.max(1, chunks.size() - 1)));
    }

    private void seekReadingByProgress(int progress) {
        String uri = prefs.getString("lastTextUri", "");
        if (uri == null || uri.isEmpty()) return;
        List<String> chunks = getCachedTextChunks(uri);
        if (chunks.isEmpty()) return;
        int index = Math.max(0, Math.min(chunks.size() - 1, progress * chunks.size() / 1001));
        seekReadingChunk(index);
    }

    private void seekReadingByDelta(int delta) {
        String uri = prefs.getString("lastTextUri", "");
        if (uri == null || uri.isEmpty()) return;
        List<String> chunks = getCachedTextChunks(uri);
        if (chunks.isEmpty()) return;
        int current = Math.max(0, Math.min(prefs.getInt("ttsChunk:" + uri, 0), chunks.size() - 1));
        seekReadingChunk(current + delta);
    }

    private void seekReadingChunk(int index) {
        seekReadingPosition(new TtsText.Position(index, 0));
    }

    private void seekReadingPosition(TtsText.Position position) {
        String uri = prefs.getString("lastTextUri", "");
        if (uri == null || uri.isEmpty()) return;
        List<String> chunks = getCachedTextChunks(uri);
        if (chunks.isEmpty()) return;
        int safe = Math.max(0, Math.min(chunks.size() - 1, position.chunk));
        int offset = TtsText.sentenceAt(chunks.get(safe), position.offset).start;
        prefs.edit().putInt("ttsChunk:" + uri, safe).putInt("ttsSentenceChunk:" + uri, safe)
                .putInt("ttsSentenceStart:" + uri, offset).putInt("ttsSentenceEnd:" + uri, offset)
                .putInt("ttsRangeStart:" + uri, offset).putInt("ttsRangeEnd:" + uri, offset)
                .putString("ttsVisualMode:" + uri, "sentence").apply();
        boolean loaded = TextReaderService.isReading(uri);
        startService(new Intent(this, TextReaderService.class)
                .setAction(loaded ? TextReaderService.ACTION_SEEK_CHUNK : TextReaderService.ACTION_START)
                .putExtra(TextReaderService.EXTRA_TEXT_URI, uri)
                .putExtra(TextReaderService.EXTRA_CHUNK_INDEX, safe)
                .putExtra(TextReaderService.EXTRA_SENTENCE_OFFSET, offset));
        updateUi();
    }

    private void sendTextReaderAction(String action) {
        startService(new Intent(this, TextReaderService.class).setAction(action));
    }

    private String nowPlayingBody(long positionMs) {
        String mediaUri = currentMediaUri();
        String timed = currentTimedText(mediaUri, positionMs);
        if (!timed.isEmpty()) return timed;
        if (TextReaderService.isRunning() && (player == null || !player.isPlaying())) {
            String reading = currentReadingChunk();
            if (!reading.isEmpty()) return reading;
        }
        if (player != null && player.getCurrentMediaItem() != null) {
            CharSequence t = player.getCurrentMediaItem().mediaMetadata.title;
            return (t == null ? "地洞播放器" : t) + "\n暂无歌词";
        }
        return "耳朵在树洞里听见了声音";
    }

    private String currentMediaUri() {
        if (player == null || player.getCurrentMediaItem() == null) return "";
        MediaItem item = player.getCurrentMediaItem();
        if (item.localConfiguration == null) return "";
        return item.localConfiguration.uri.toString();
    }

    private void showReadingCatalogDialog() {
        String uri = prefs.getString("lastTextUri", "");
        if (uri == null || uri.isEmpty()) return;
        try {
            ReadableBook book = BookLoader.load(this, Uri.parse(uri));
            List<String> chunks = splitTextForTts(book.text);
            if (chunks.isEmpty()) return;
            List<TtsText.Position> targets = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            if (!book.chapters.isEmpty()) {
                for (ReadableBook.Chapter chapter : book.chapters) {
                    targets.add(TtsText.positionForOffset(book.text, chunks, chapter.offset));
                    labels.add((chapter.depth > 0 ? "↳ " : "") + chapter.title);
                }
            } else {
                for (int i = 0; i < chunks.size(); i++) {
                    String preview = chunkPreview(chunks.get(i));
                    if (looksLikeChapter(preview) || labels.size() < 80) {
                        targets.add(new TtsText.Position(i, 0));
                        labels.add((i + 1) + ". " + preview);
                    }
                }
            }
            new AlertDialog.Builder(this).setTitle("章节目录")
                    .setItems(labels.toArray(new String[0]), (dialog, which) -> seekReadingPosition(targets.get(which)))
                    .setNegativeButton("关闭", null).show();
        } catch (java.io.IOException error) {
            android.widget.Toast.makeText(this, "目录读取失败：" + error.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void addReadingBookmark() {
        String uri = prefs.getString("lastTextUri", "");
        if (uri == null || uri.isEmpty()) return;
        int index = prefs.getInt("ttsChunk:" + uri, 0);
        JSONArray array = loadReadingBookmarks(uri);
        for (int i = 0; i < array.length(); i++) {
            if (array.optInt(i, -1) == index) return;
        }
        array.put(index);
        prefs.edit().putString("textMarks:" + uri, array.toString()).apply();
    }

    private void showReadingBookmarksDialog() {
        String uri = prefs.getString("lastTextUri", "");
        if (uri == null || uri.isEmpty()) return;
        List<String> chunks = splitTextForTts(readText(Uri.parse(uri)));
        JSONArray marks = loadReadingBookmarks(uri);
        if (chunks.isEmpty() || marks.length() == 0) {
            new AlertDialog.Builder(this)
                    .setTitle("\u4e66\u7b7e")
                    .setMessage("\u6682\u65e0\u4e66\u7b7e")
                    .setPositiveButton("\u786e\u5b9a", null)
                    .show();
            return;
        }
        List<Integer> targets = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < marks.length(); i++) {
            int target = Math.max(0, Math.min(chunks.size() - 1, marks.optInt(i, 0)));
            targets.add(target);
            labels.add((target + 1) + ". " + chunkPreview(chunks.get(target)));
        }
        new AlertDialog.Builder(this)
                .setTitle("\u4e66\u7b7e")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> seekReadingChunk(targets.get(which)))
                .setNeutralButton("\u6e05\u7a7a", (dialog, which) -> prefs.edit().remove("textMarks:" + uri).apply())
                .setNegativeButton("\u5173\u95ed", null)
                .show();
    }

    private void showReadingSearchDialog() {
        String uri = prefs.getString("lastTextUri", "");
        if (uri == null || uri.isEmpty()) return;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("\u8f93\u5165\u8981\u641c\u7d22\u7684\u6587\u5b57");
        new AlertDialog.Builder(this)
                .setTitle("\u641c\u7d22\u6587\u672c")
                .setView(input)
                .setPositiveButton("\u641c\u7d22", (dialog, which) -> showReadingSearchResults(uri, input.getText().toString()))
                .setNegativeButton("\u53d6\u6d88", null)
                .show();
    }

    private void showReadingSearchResults(String uri, String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return;
        List<String> chunks = splitTextForTts(readText(Uri.parse(uri)));
        List<Integer> targets = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < chunks.size() && labels.size() < 50; i++) {
            if (chunks.get(i).contains(q)) {
                targets.add(i);
                labels.add((i + 1) + ". " + chunkPreview(chunks.get(i)));
            }
        }
        if (labels.isEmpty()) labels.add("\u6ca1\u6709\u627e\u5230");
        new AlertDialog.Builder(this)
                .setTitle("\u641c\u7d22\u7ed3\u679c")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (!targets.isEmpty()) seekReadingChunk(targets.get(which));
                })
                .setNegativeButton("\u5173\u95ed", null)
                .show();
    }

    private JSONArray loadReadingBookmarks(String uri) {
        try {
            return new JSONArray(prefs.getString("textMarks:" + uri, "[]"));
        } catch (JSONException ignored) {
            return new JSONArray();
        }
    }

    private boolean looksLikeChapter(String text) {
        String value = text == null ? "" : text.trim();
        return value.startsWith("\u7b2c") && (value.contains("\u7ae0") || value.contains("\u8282") || value.contains("\u56de"))
                || value.startsWith("Chapter ") || value.startsWith("chapter ");
    }

    private String chunkPreview(String text) {
        String value = text == null ? "" : text.replace('\n', ' ').trim();
        return value.length() > 42 ? value.substring(0, 42) : value;
    }

    private String currentTimedText(String mediaUri, long positionMs) {
        if (mediaUri.isEmpty()) return "";
        long offset = prefs.getLong("timedOffset:" + mediaUri, 0);
        String raw = prefs.getString("timed:" + mediaUri, "[]");
        long adjusted = positionMs - offset;
        String current = "";
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                long timeMs = obj.optLong("t", 0);
                if (timeMs <= adjusted + 250) current = obj.optString("x", ""); else break;
            }
        } catch (JSONException ignored) {
        }
        return current == null ? "" : current.trim();
    }

    private String currentReadingChunk() {
        String uri = prefs.getString("lastTextUri", "");
        if (uri.isEmpty()) return "";
        List<String> chunks = getCachedTextChunks(uri);
        if (chunks.isEmpty()) return "";
        int index = Math.max(0, Math.min(prefs.getInt("ttsChunk:" + uri, 0), chunks.size() - 1));
        int start = prefs.getInt("ttsSentenceStart:" + uri, prefs.getInt("ttsRangeStart:" + uri, 0));
        int end = prefs.getInt("ttsSentenceEnd:" + uri, start);
        return readingWindowText(chunks, index, start, end);
    }

    private String readingWindowText(List<String> chunks, int index, int rangeStart, int rangeEnd) {
        return TtsText.readingWindow(chunks, index, rangeStart);
    }



    private List<String> splitTextForTts(String text) {
        return TtsText.splitChunks(text);
    }

    private String readText(Uri uri) {
        try {
            return BookLoader.load(this, uri).text;
        } catch (java.io.IOException | RuntimeException error) {
            android.util.Log.w("BookReader", "读取电子书失败", error);
            return "";
        }
    }





    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
        }
        String name = uri.getLastPathSegment();
        return name == null ? "" : name;
    }









    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            unbindService(connection);
        } catch (IllegalArgumentException ignored) {
        }
        super.onDestroy();
    }

    private LinearLayout row(android.view.View... views) {
        return UiKit.row(this, views);
    }

    private Button btn(String text, android.view.View.OnClickListener listener) {
        return UiKit.button(this, text, listener);
    }

    private Button chip(String text, android.view.View.OnClickListener listener) {
        return UiKit.chip(this, text, listener);
    }

    private TextView label(String text, int sp, int color) {
        return UiKit.label(this, text, sp, color);
    }

    private int dp(int value) {
        return UiKit.dp(this, value);
    }
}
