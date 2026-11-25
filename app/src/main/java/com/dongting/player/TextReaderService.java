package com.dongting.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class TextReaderService extends Service implements TextToSpeech.OnInitListener {
    static final String ACTION_START = "com.dongting.player.TEXT_START";
    static final String ACTION_TOGGLE = "com.dongting.player.TEXT_TOGGLE";
    static final String ACTION_PREVIOUS = "com.dongting.player.TEXT_PREVIOUS";
    static final String ACTION_NEXT = "com.dongting.player.TEXT_NEXT";
    static final String ACTION_RESTART = "com.dongting.player.TEXT_RESTART";
    static final String ACTION_STOP = "com.dongting.player.TEXT_STOP";
    static final String ACTION_SEEK_CHUNK = "com.dongting.player.TEXT_SEEK_CHUNK";
    static final String ACTION_BACKGROUND_UPDATE = "com.dongting.player.TEXT_BACKGROUND_UPDATE";
    static final String ACTION_BACKGROUND_STOP = "com.dongting.player.TEXT_BACKGROUND_STOP";

    static final String EXTRA_TEXT_URI = "text_uri";
    static final String EXTRA_CHUNK_INDEX = "chunk_index";
    static final String EXTRA_SENTENCE_OFFSET = "sentence_offset";
    static final String EXTRA_TEXT_CONTENT = "text_content";

    private static final String PREFS = "dongting_player";
    private static final String CHANNEL_ID = "dongting_text_reader";
    private static final int NOTIFICATION_ID = 20260503;
    private static final int MAX_TTS_ERRORS = 5;
    private static boolean serviceRunning = false;
    private static boolean servicePaused = true;
    private static boolean rangeTrackingSupported = false;
    private static String activeTextUri = "";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<String> chunks = new ArrayList<>();
    private SharedPreferences prefs;
    private TextToSpeech tts;
    private ExoPlayer bgPlayer;
    private ExoPlayer externalTtsPlayer;
    private String textUri = "";
    private String title = "电子书朗读";
    private int currentChunk = 0;
    private int currentSpeakOffset = 0;
    private int currentSpeakEnd = 0;
    private int nextSpeakOffset = 0;
    private long utteranceGeneration = 0;
    private String activeUtteranceId = "";
    private boolean externalPlaybackActive = false;
    private int consecutiveErrors = 0;
    private boolean ready = false;
    private boolean paused = false;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean focusRequested = false;
    private boolean noisyReceiverRegistered = false;
    private PowerManager.WakeLock wakeLock;
    private android.graphics.Bitmap defaultArtwork;

    /** 来电、其它应用占用音频时暂停朗读，不自动恢复。 */
    private final AudioManager.OnAudioFocusChangeListener focusListener = focusChange -> {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            pauseForExternal("来电或音频被占用，朗读已暂停");
        }
    };

    /** 拔掉耳机 / 蓝牙断连时暂停朗读，不自动恢复。 */
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                pauseForExternal("耳机已断开，朗读已暂停");
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        serviceRunning = true;
        servicePaused = true;
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        createNotificationChannel();
        registerNoisyReceiver();
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build();
        bgPlayer = new ExoPlayer.Builder(this).setAudioAttributes(attrs, false).build();
        // 焦点由本服务统一管理，避免 ExoPlayer 在焦点恢复时自动续播
        externalTtsPlayer = new ExoPlayer.Builder(this).setAudioAttributes(attrs, false).build();
        externalTtsPlayer.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    prefs.edit()
                            .putString("lastExternalTtsState", "READY")
                            .putString("ttsVisualMode:" + textUri, "sentence")
                            .apply();
                }
                long generation = utteranceGeneration;
                if (playbackState == Player.STATE_ENDED && externalPlaybackActive) mainHandler.post(() -> {
                    if (!externalPlaybackActive || generation != utteranceGeneration) return;
                    prefs.edit().putString("lastExternalTtsState", "ENDED").apply();
                    if (paused) return;
                    consecutiveErrors = 0;
                    advanceSentence();
                    speakCurrent();
                });
            }

            @Override public void onPlayerError(PlaybackException error) {
                long generation = utteranceGeneration;
                mainHandler.post(() -> {
                    if (!paused && externalPlaybackActive && generation == utteranceGeneration && !chunks.isEmpty()) {
                        prefs.edit()
                                .putString("lastExternalTtsState", "ERROR")
                                .putString("lastExternalTtsError", error.getErrorCodeName())
                                .apply();
                        fallbackFromExternalTts();
                    }
                });
            }
        });
        applyBackgroundRepeatMode();
        createTtsFromSystemDefault();
    }

    private void registerNoisyReceiver() {
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(noisyReceiver, filter);
            }
            noisyReceiverRegistered = true;
        } catch (RuntimeException ignored) {
        }
    }

    private void requestReadingFocus() {
        if (audioManager == null) return;
        try {
            if (focusRequest == null) {
                android.media.AudioAttributes platformAttrs = new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(platformAttrs)
                        .setOnAudioFocusChangeListener(focusListener, new Handler(Looper.getMainLooper()))
                        .setAcceptsDelayedFocusGain(false)
                        .build();
            }
            focusRequested = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (!focusRequested) pauseReading();
        } catch (RuntimeException ignored) {
            focusRequested = false;
        }
    }

    private void abandonReadingFocus() {
        if (audioManager == null || focusRequest == null || !focusRequested) return;
        try {
            audioManager.abandonAudioFocusRequest(focusRequest);
        } catch (RuntimeException ignored) {
        }
        focusRequested = false;
    }

    private void pauseForExternal(String reason) {
        pauseReading();
        markPauseReason(reason);
        updateNotification(false);
    }

    private void markPauseReason(String reason) {
        prefs.edit()
                .putString("lastPauseReason", reason)
                .putLong("lastPauseReasonAt", System.currentTimeMillis())
                .apply();
    }

    /** 后台朗读息屏后保持 CPU 唤醒，避免长文本读一半断流。 */
    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager manager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (manager != null) {
                wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dongting:TextReader");
                wakeLock.setReferenceCounted(false);
            }
        }
        if (wakeLock != null && !wakeLock.isHeld()) {
            try {
                wakeLock.acquire(10 * 60 * 1000L);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void createTtsFromSystemDefault() {
        String engine = currentSystemTtsEngine();
        if (engine.isEmpty()) {
            tts = new TextToSpeech(this, this);
        } else {
            tts = new TextToSpeech(this, this, engine);
        }
    }

    private String currentSystemTtsEngine() {
        try {
            String engine = android.provider.Settings.Secure.getString(getContentResolver(), "tts_default_synth");
            return engine == null ? "" : engine.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            // 用户主动开始朗读：解除暂停态，清掉"摘耳机/来电"的遗留标记
            paused = false;
            servicePaused = false;
            prefs.edit().remove("lastPauseReason").apply();
            String uri = intent.getStringExtra(EXTRA_TEXT_URI);
            String content = intent.getStringExtra(EXTRA_TEXT_CONTENT);
            if (content != null && !content.trim().isEmpty()) {
                textUri = "inline:" + content.hashCode();
                activeTextUri = textUri;
                title = "电子书朗读";
                currentChunk = Math.max(0, prefs.getInt("ttsChunk:" + textUri, 0));
                paused = false;
                loadTextContent(content);
                if (ready || useExternalTts()) speakCurrent();
                updateNotification(true);
            } else if (uri != null && !uri.isEmpty()) {
                textUri = uri;
                activeTextUri = textUri;
                title = readDisplayName(Uri.parse(uri));
                currentChunk = Math.max(0, prefs.getInt("ttsChunk:" + textUri, 0));
                paused = false;
                if (!loadText(Uri.parse(uri))) {
                    stopReading();
                    return START_NOT_STICKY;
                }
                if (ready || useExternalTts()) speakCurrent();
                updateNotification(true);
            }
        } else if (ACTION_TOGGLE.equals(action)) {
            toggle();
        } else if (ACTION_PREVIOUS.equals(action)) {
            moveChunk(-1);
        } else if (ACTION_NEXT.equals(action)) {
            moveChunk(1);
        } else if (ACTION_RESTART.equals(action)) {
            currentChunk = 0;
            nextSpeakOffset = 0;
            if (!textUri.isEmpty()) prefs.edit().putInt("ttsChunk:" + textUri, 0).apply();
            // 从通知栏点"从头读"即使用户原先暂停，也应开始朗读
            paused = false;
            servicePaused = false;
            prefs.edit().remove("lastPauseReason").apply();
            updateNotification(true);
            speakCurrent();
        } else if (ACTION_SEEK_CHUNK.equals(action)) {
            seekChunk(intent.getIntExtra(EXTRA_CHUNK_INDEX, currentChunk), intent.getIntExtra(EXTRA_SENTENCE_OFFSET, 0));
        } else if (ACTION_STOP.equals(action)) {
            stopReading();
        } else if (ACTION_BACKGROUND_UPDATE.equals(action)) {
            updateBackgroundMusicFromPrefs();
        } else if (ACTION_BACKGROUND_STOP.equals(action)) {
            if (bgPlayer != null) {
                bgPlayer.pause();
                bgPlayer.seekTo(0);
            }
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onInit(int status) {
        ready = status == TextToSpeech.SUCCESS;
        if (!ready) {
            updateNotification(false);
            return;
        }
        tts.setLanguage(Locale.CHINA);
        applyTtsSettings();
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {
            }

            @Override public void onDone(String utteranceId) {
                mainHandler.post(() -> {
                    if (paused || !utteranceId.equals(activeUtteranceId)) return;
                    consecutiveErrors = 0;
                    advanceSentence();
                    speakCurrent();
                });
            }

            @Override public void onError(String utteranceId) {
                mainHandler.post(() -> {
                    if (utteranceId.equals(activeUtteranceId)) continueAfterTtsError();
                });
            }

            @Override public void onRangeStart(String utteranceId, int start, int end, int frame) {
                mainHandler.post(() -> {
                    if (paused || textUri.isEmpty() || !utteranceId.equals(activeUtteranceId)) return;
                    rangeTrackingSupported = true;
                    int safeStart = Math.max(currentSpeakOffset, Math.min(currentSpeakEnd, currentSpeakOffset + start));
                    int safeEnd = Math.max(safeStart, Math.min(currentSpeakEnd, currentSpeakOffset + end));
                    prefs.edit()
                            .putInt("ttsRangeStart:" + textUri, safeStart)
                            .putInt("ttsRangeEnd:" + textUri, safeEnd)
                            .apply();
                });
            }
        });
        if (!useExternalTts() && !textUri.isEmpty() && !chunks.isEmpty()) speakCurrent();
    }

    @Override
    public void onDestroy() {
        paused = true;
        externalPlaybackActive = false;
        serviceRunning = false;
        activeTextUri = "";
        servicePaused = true;
        activeUtteranceId = "";
        mainHandler.removeCallbacksAndMessages(null);
        if (!textUri.isEmpty()) prefs.edit().putInt("ttsChunk:" + textUri, currentChunk).apply();
        if (noisyReceiverRegistered) {
            try {
                unregisterReceiver(noisyReceiver);
            } catch (RuntimeException ignored) {
            }
            noisyReceiverRegistered = false;
        }
        abandonReadingFocus();
        releaseWakeLock();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (externalTtsPlayer != null) externalTtsPlayer.release();
        if (bgPlayer != null) bgPlayer.release();
        super.onDestroy();
    }

    static boolean isRunning() {
        return serviceRunning;
    }

    static boolean isReading(String uri) {
        return serviceRunning && uri.equals(activeTextUri);
    }

    static boolean isPaused() {
        return servicePaused;
    }

    static boolean isRangeTrackingSupported() {
        return rangeTrackingSupported;
    }

    private boolean loadText(Uri uri) {
        chunks.clear();
        String text = readText(uri);
        chunks.addAll(splitTextForTts(text));
        if (chunks.isEmpty()) return false;
        currentChunk = Math.max(0, prefs.getInt("ttsChunk:" + textUri, currentChunk));
        currentChunk = Math.min(currentChunk, Math.max(0, chunks.size() - 1));
        restoreSentenceOffset();
        return true;
    }

    private void loadTextContent(String text) {
        chunks.clear();
        chunks.addAll(splitTextForTts(text == null ? "" : text));
        currentChunk = Math.min(currentChunk, Math.max(0, chunks.size() - 1));
        restoreSentenceOffset();
    }

    private void restoreSentenceOffset() {
        // Older builds used a different service splitter. UI paragraph coordinates are
        // authoritative from this version onward; discard incompatible word offsets.
        if (!"sentence".equals(prefs.getString("ttsVisualMode:" + textUri, ""))) {
            nextSpeakOffset = 0;
            return;
        }
        if (prefs.getInt("ttsSentenceChunk:" + textUri, -1) != currentChunk) {
            nextSpeakOffset = 0;
            return;
        }
        nextSpeakOffset = prefs.getInt("ttsSentenceStart:" + textUri,
                prefs.getInt("ttsRangeStart:" + textUri, 0));
    }

    private void advanceSentence() {
        activeUtteranceId = "";
        externalPlaybackActive = false;
        nextSpeakOffset = currentSpeakEnd;
        if (nextSpeakOffset >= currentSpeakText().length()
                || currentSpeakText().substring(nextSpeakOffset).trim().isEmpty()) {
            currentChunk++;
            nextSpeakOffset = 0;
        }
        if (!textUri.isEmpty()) prefs.edit()
                .putInt("ttsChunk:" + textUri, currentChunk)
                .putInt("ttsSentenceStart:" + textUri, nextSpeakOffset)
                .putInt("ttsRangeStart:" + textUri, nextSpeakOffset)
                .putInt("ttsRangeEnd:" + textUri, nextSpeakOffset).apply();
    }

    private void speakCurrent() {
        if (chunks.isEmpty()) return;
        if (paused) return; // 外部暂停后，只有用户显式点播放才会重新进入这里
        if (!useExternalTts() && (!ready || tts == null)) return;
        requestReadingFocus();
        if (paused) return;
        acquireWakeLock();
        if (currentChunk >= chunks.size()) {
            if (!textUri.isEmpty()) prefs.edit().putInt("ttsChunk:" + textUri, 0).apply();
            if (playNextTextBook()) return;
            currentChunk = 0;
            nextSpeakOffset = 0;
            stopReading();
            return;
        }
        String chunk = currentSpeakText();
        TtsText.Sentence sentence = TtsText.sentenceAt(chunk, nextSpeakOffset);
        currentSpeakOffset = sentence.start;
        currentSpeakEnd = sentence.end;
        nextSpeakOffset = sentence.start;
        String speakText = sentence.text(chunk);
        activeUtteranceId = "";
        externalPlaybackActive = false;
        utteranceGeneration++;
        if (!textUri.isEmpty()) {
            prefs.edit()
                    .putInt("ttsChunk:" + textUri, currentChunk)
                    .putInt("ttsSentenceStart:" + textUri, currentSpeakOffset)
                    .putInt("ttsSentenceChunk:" + textUri, currentChunk)
                    .putInt("ttsSentenceEnd:" + textUri, currentSpeakEnd)
                    .putInt("ttsRangeStart:" + textUri, currentSpeakOffset)
                    .putInt("ttsRangeEnd:" + textUri, Math.min(chunk.length(), currentSpeakOffset + 1))
                    .putString("ttsVisualMode:" + textUri, "sentence")
                    .apply();
        }
        paused = false;
        servicePaused = false;
        applyTtsSettings();
        startBackgroundMusic();
        updateNotification(true);
        if (useExternalTts()) {
            speakCurrentWithExternalTts(speakText);
            return;
        }
        speakCurrentWithSystemTts(speakText);
    }

    private String currentSpeakText() {
        if (chunks.isEmpty()) return "";
        return chunks.get(Math.max(0, Math.min(currentChunk, chunks.size() - 1)));
    }

    private boolean playNextTextBook() {
        String next = nextTextBookUri();
        if (next.isEmpty()) return false;
        textUri = next;
        activeTextUri = textUri;
        title = readDisplayName(Uri.parse(next));
        currentChunk = Math.max(0, prefs.getInt("ttsChunk:" + textUri, 0));
        if (!loadText(Uri.parse(next))) return playNextTextBook();
        prefs.edit().putString("lastTextUri", textUri).apply();
        speakCurrent();
        return true;
    }

    private String nextTextBookUri() {
        if (textUri == null || textUri.isEmpty() || textUri.startsWith("inline:")) return "";
        List<String> books = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString("textBooks", "[]"));
            for (int i = 0; i < array.length(); i++) {
                String uri = array.getJSONObject(i).optString("uri", "");
                if (!uri.isEmpty()) books.add(uri);
            }
        } catch (JSONException ignored) {
        }
        int index = books.indexOf(textUri);
        if (index < 0 || index + 1 >= books.size()) return "";
        return books.get(index + 1);
    }

    private void speakCurrentWithSystemTts(String speakText) {
        if (!ready || tts == null || speakText == null || speakText.isEmpty()) return;
        externalPlaybackActive = false;
        if (externalTtsPlayer != null) externalTtsPlayer.stop();
        activeUtteranceId = "dongting_reader_" + (++utteranceGeneration);
        int result = tts.speak(speakText, TextToSpeech.QUEUE_FLUSH, null, activeUtteranceId);
        if (result == TextToSpeech.ERROR) continueAfterTtsError();
    }

    private void speakCurrentWithExternalTts(String speakText) {
        if (externalTtsPlayer == null || speakText == null || speakText.trim().isEmpty()) {
            speakCurrentWithSystemTts(speakText);
            return;
        }
        if (tts != null) tts.stop();
        try {
            Uri uri = buildExternalTtsUri(speakText);
            prefs.edit()
                    .putString("lastExternalTtsState", "REQUEST")
                    .putString("lastExternalTtsError", "")
                    .putString("lastExternalTtsInfo", "chunk=" + currentChunk + ", chars=" + speakText.length()
                            + ", voice=" + prefs.getString("externalTtsVoice", ""))
                    .apply();
            externalTtsPlayer.stop();
            externalTtsPlayer.clearMediaItems();
            externalPlaybackActive = true;
            externalTtsPlayer.setMediaItem(MediaItem.fromUri(uri));
            externalTtsPlayer.prepare();
            externalTtsPlayer.play();
        } catch (Exception ex) {
            prefs.edit().putString("lastExternalTtsError", ex.getClass().getSimpleName()).apply();
            fallbackFromExternalTts();
        }
    }

    private void fallbackFromExternalTts() {
        if (paused || chunks.isEmpty()) return;
        if (ready && tts != null) {
            String chunk = currentSpeakText();
            speakCurrentWithSystemTts(chunk.substring(currentSpeakOffset, currentSpeakEnd));
            return;
        }
        continueAfterTtsError();
    }

    private Uri buildExternalTtsUri(String text) throws Exception {
        String base = prefs.getString("externalTtsForwardUrl", "http://127.0.0.1:8774/forward");
        if (base == null || base.trim().isEmpty()) base = "http://127.0.0.1:8774/forward";
        String separator = base.contains("?") ? "&" : "?";
        int speed = Math.max(0, Math.min(100, Math.round(prefs.getInt("ttsRate", 75) * 100f / 150f)));
        int pitch = Math.max(0, Math.min(100, prefs.getInt("ttsPitch", 50)));
        int volume = Math.max(0, Math.min(100, prefs.getInt("externalTtsVolume", 100)));
        String voice = prefs.getString("externalTtsVoice", "");
        StringBuilder url = new StringBuilder(base.trim())
                .append(separator)
                .append("text=").append(URLEncoder.encode(text, "UTF-8"))
                .append("&speed=").append(speed)
                .append("&volume=").append(volume)
                .append("&pitch=").append(pitch);
        if (voice != null && !voice.trim().isEmpty()) {
            url.append("&voice=").append(URLEncoder.encode(voice.trim(), "UTF-8"));
        }
        return Uri.parse(url.toString());
    }

    private boolean useExternalTts() {
        return "multitts".equals(prefs.getString("ttsProvider", "system"));
    }

    private void continueAfterTtsError() {
        if (paused || chunks.isEmpty()) return;
        consecutiveErrors++;
        advanceSentence();
        if (consecutiveErrors >= MAX_TTS_ERRORS) {
            pauseReading();
            return;
        }
        long generation = utteranceGeneration;
        mainHandler.postDelayed(() -> {
            if (generation == utteranceGeneration) speakCurrent();
        }, 300);
    }

    private void toggle() {
        if (paused) {
            // 用户显式点播放：解除外部暂停标记，恢复前台通知与唤醒锁
            paused = false;
            servicePaused = false;
            prefs.edit().remove("lastPauseReason").apply();
            updateNotification(true);
            if (useExternalTts() && externalPlaybackActive && externalTtsPlayer != null
                    && externalTtsPlayer.getMediaItemCount() > 0
                    && externalTtsPlayer.getPlaybackState() != Player.STATE_ENDED) {
                startBackgroundMusic();
                requestReadingFocus();
                if (paused) return;
                acquireWakeLock();
                externalTtsPlayer.play();
            } else {
                // 原来这里没有清 paused，系统 TTS 读完一段就会停；一并修复
                speakCurrent();
            }
        } else {
            pauseReading();
        }
    }

    private void moveChunk(int delta) {
        if (chunks.isEmpty()) return;
        seekChunk(currentChunk + delta);
    }

    private void seekChunk(int index) {
        seekChunk(index, 0);
    }

    private void seekChunk(int index, int offset) {
        if (chunks.isEmpty()) return;
        currentChunk = Math.max(0, Math.min(chunks.size() - 1, index));
        nextSpeakOffset = TtsText.sentenceAt(currentSpeakText(), Math.max(0, offset)).start;
        if (!textUri.isEmpty()) {
            prefs.edit()
                    .putInt("ttsChunk:" + textUri, currentChunk)
                    .putInt("ttsRangeStart:" + textUri, 0)
                    .putInt("ttsRangeEnd:" + textUri, 0)
                    .apply();
        }
        paused = false;
        servicePaused = false;
        updateNotification(true);
        speakCurrent();
    }

    private void pauseReading() {
        paused = true;
        servicePaused = true;
        activeUtteranceId = "";
        utteranceGeneration++;
        if (tts != null) tts.stop();
        if (externalTtsPlayer != null) externalTtsPlayer.pause();
        if (bgPlayer != null) bgPlayer.pause();
        abandonReadingFocus();
        releaseWakeLock();
        if (!textUri.isEmpty()) prefs.edit().putInt("ttsChunk:" + textUri, currentChunk).apply();
        updateNotification(false);
    }

    private void stopReading() {
        paused = true;
        servicePaused = true;
        activeUtteranceId = "";
        externalPlaybackActive = false;
        utteranceGeneration++;
        if (tts != null) tts.stop();
        if (externalTtsPlayer != null) externalTtsPlayer.stop();
        if (bgPlayer != null) bgPlayer.pause();
        abandonReadingFocus();
        releaseWakeLock();
        if (!textUri.isEmpty()) prefs.edit().putInt("ttsChunk:" + textUri, currentChunk).apply();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void applyTtsSettings() {
        if (tts == null) return;
        float rate = 0.5f + (prefs.getInt("ttsRate", 75) / 100f);
        float pitch = 0.5f + (prefs.getInt("ttsPitch", 50) / 100f);
        tts.setSpeechRate(rate);
        tts.setPitch(pitch);
        String savedVoice = prefs.getString("ttsVoice", "");
        Set<Voice> voices = tts.getVoices();
        if (!savedVoice.isEmpty() && voices != null) {
            for (Voice voice : voices) {
                if (savedVoice.equals(voice.getName())) {
                    tts.setVoice(voice);
                    break;
                }
            }
        }
    }

    private void startBackgroundMusic() {
        if (bgPlayer == null) return;
        if (bgPlayer.getMediaItemCount() == 0) prepareBackgroundMusicQueue();
        if (bgPlayer.getMediaItemCount() == 0) return;
        applyBackgroundMusicVolume();
        bgPlayer.play();
    }

    private void prepareBackgroundMusicQueue() {
        if (bgPlayer == null) return;
        List<String> uris = loadBackgroundMusicUris();
        bgPlayer.clearMediaItems();
        for (String raw : uris) bgPlayer.addMediaItem(MediaItem.fromUri(Uri.parse(raw)));
        if (!uris.isEmpty()) {
            applyBackgroundRepeatMode();
            bgPlayer.prepare();
            bgPlayer.seekToDefaultPosition(selectedBackgroundIndex(uris.size()));
        }
    }

    private List<String> loadBackgroundMusicUris() {
        List<String> uris = new ArrayList<>();
        String raw = prefs.getString("bgPlaylist", "");
        if (raw != null && !raw.isEmpty()) {
            try {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    String uri = array.optString(i, "");
                    if (!uri.isEmpty() && !uris.contains(uri)) uris.add(uri);
                }
            } catch (JSONException ignored) {
            }
        }
        String legacy = prefs.getString("ttsBgUri", "");
        if (!legacy.isEmpty() && !uris.contains(legacy)) uris.add(legacy);
        return uris;
    }

    private void applyBackgroundMusicVolume() {
        if (bgPlayer == null) return;
        bgPlayer.setVolume(prefs.getInt("bgVolume", 25) / 100f);
    }

    private void updateBackgroundMusicFromPrefs() {
        if (bgPlayer == null) return;
        List<String> uris = loadBackgroundMusicUris();
        boolean wasPlaying = bgPlayer.isPlaying();
        bgPlayer.clearMediaItems();
        for (String raw : uris) bgPlayer.addMediaItem(MediaItem.fromUri(Uri.parse(raw)));
        applyBackgroundRepeatMode();
        if (!uris.isEmpty()) {
            bgPlayer.prepare();
            bgPlayer.seekToDefaultPosition(selectedBackgroundIndex(uris.size()));
            if (wasPlaying) bgPlayer.play();
        }
        applyBackgroundMusicVolume();
    }

    private int selectedBackgroundIndex(int size) {
        if (size <= 0) return 0;
        return Math.max(0, Math.min(prefs.getInt("bgSelectedIndex", 0), size - 1));
    }

    private void applyBackgroundRepeatMode() {
        if (bgPlayer == null) return;
        bgPlayer.setRepeatMode(prefs.getBoolean("bgRepeatOne", false)
                ? androidx.media3.common.Player.REPEAT_MODE_ONE
                : androidx.media3.common.Player.REPEAT_MODE_ALL);
    }

    private void updateNotification(boolean reading) {
        Notification notification = buildNotification(reading);
        // 暂停时同样保持前台，避免后台朗读服务被系统回收
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable ignored) {
        }
    }

    private Notification buildNotification(boolean reading) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 30, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String progress = chunks.isEmpty() ? "准备朗读" : "第 " + Math.min(currentChunk + 1, chunks.size()) + "/" + chunks.size() + " 段";
        String state = reading ? "正在朗读 · " : "已暂停 · 点继续 · ";
        if (!reading) {
            String reason = prefs.getString("lastPauseReason", "");
            if (!reason.isEmpty()) state = reason + " · 点继续 · ";
        }
        Notification.Builder builder = new Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(state + progress)
                .setSmallIcon(R.drawable.ic_stat_dongting)
                .setColor(UiKit.ACCENT)
                .setContentIntent(contentIntent)
                .setOngoing(reading)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_media_previous, "上一段", action(ACTION_PREVIOUS, 33))
                .addAction(reading ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        reading ? "暂停" : "继续", action(ACTION_TOGGLE, 31))
                .addAction(android.R.drawable.ic_media_next, "下一段", action(ACTION_NEXT, 34))
                .addAction(android.R.drawable.ic_menu_revert, "从头读", action(ACTION_RESTART, 35))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", action(ACTION_STOP, 32));
        if (defaultArtwork == null) {
            try {
                defaultArtwork = android.graphics.BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher);
            } catch (Throwable ignored) {
            }
        }
        if (defaultArtwork != null) {
            builder.setLargeIcon(defaultArtwork);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setChannelId(CHANNEL_ID);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) builder.setVisibility(Notification.VISIBILITY_PUBLIC);
        return builder.build();
    }

    private PendingIntent action(String action, int requestCode) {
        Intent intent = new Intent(this, TextReaderService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "地洞朗读",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("地洞播放器 TXT 后台朗读控制");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private String readText(Uri uri) {
        try {
            return BookLoader.load(this, uri).text;
        } catch (java.io.IOException | RuntimeException error) {
            android.util.Log.w("BookReader", "读取电子书失败", error);
            return "";
        }
    }













    private String readDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
        }
        String name = uri.getLastPathSegment();
        return name == null || name.isEmpty() ? "电子书朗读" : name;
    }

    private List<String> splitTextForTts(String text) {
        return TtsText.splitChunks(text);
    }
}
