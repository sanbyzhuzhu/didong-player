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
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.session.MediaSession.Token;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;

/**
 * 后台播放服务。
 *
 * 行为约定（与用户确认）：拔掉耳机、来电、其它应用抢占音频后一律暂停，
 * 并且【不自动恢复】——必须由用户重新点击播放。暂停期间服务保持前台，
 * 避免被系统回收后丢失播放状态。
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class PlaybackService extends Service {
    static final String ACTION_TOGGLE = "com.dongting.player.TOGGLE";
    static final String ACTION_PREVIOUS = "com.dongting.player.PREVIOUS";
    static final String ACTION_NEXT = "com.dongting.player.NEXT";
    static final String ACTION_STOP = "com.dongting.player.STOP";

    static final String PAUSE_REASON_HEADSET = "耳机已断开";
    static final String PAUSE_REASON_CALL = "来电或其它应用占用音频";
    static final String PAUSE_REASON_FOCUS = "音频被其它应用占用";

    private static final String CHANNEL_ID = "dongting_playback";
    private static final int NOTIFICATION_ID = 20260502;

    private final LocalBinder binder = new LocalBinder();
    private ExoPlayer player;
    private MediaSession mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean focusRequested = false;
    private boolean noisyReceiverRegistered = false;
    private android.graphics.Bitmap defaultArtwork;

    private final AudioManager.OnAudioFocusChangeListener focusListener = focusChange -> {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            // 这里只暂停，绝不在 AUDIOFOCUS_GAIN 时自动恢复播放
            pauseForExternal(focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                    ? PAUSE_REASON_FOCUS : PAUSE_REASON_CALL);
        }
    };

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                pauseForExternal(PAUSE_REASON_HEADSET);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build();
        // 音频焦点由本服务自己管理：丢失即暂停、回来不自动播放。
        player = new ExoPlayer.Builder(this).setMediaSourceFactory(WebDavMedia.sources(this))
                .setAudioAttributes(attrs, false).build();
        player.setHandleAudioBecomingNoisy(true);
        player.setWakeMode(C.WAKE_MODE_LOCAL);
        mediaSession = new MediaSession.Builder(this, player).build();
        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
                    // 蓝牙耳机、线控、锁屏控件也能开始播放，一并清掉暂停原因
                    getSharedPreferences("dongting_player", MODE_PRIVATE).edit()
                            .remove("lastPauseReason").apply();
                    requestPlaybackFocus();
                } else {
                    abandonPlaybackFocus();
                }
                updateForegroundState();
            }

            @Override
            public void onMediaItemTransition(@Nullable androidx.media3.common.MediaItem mediaItem, int reason) {
                updateForegroundState();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                // 出错时保留进度、保持暂停，等用户手动重试，绝不自动跳到开头
                savePositionQuietly();
                if (player != null) player.pause();
                updateForegroundState();
            }
        });
        registerNoisyReceiver();
        updateForegroundState();
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

    private void requestPlaybackFocus() {
        if (audioManager == null) return;
        try {
            if (focusRequest == null) {
                android.media.AudioAttributes platformAttrs = new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build();
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(platformAttrs)
                        .setOnAudioFocusChangeListener(focusListener, new Handler(Looper.getMainLooper()))
                        .setAcceptsDelayedFocusGain(false)
                        .build();
            }
            int result = audioManager.requestAudioFocus(focusRequest);
            focusRequested = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (!focusRequested && player != null) {
                player.pause();
                markPauseReason(PAUSE_REASON_FOCUS);
            }
        } catch (RuntimeException ignored) {
            focusRequested = false;
        }
    }

    private void abandonPlaybackFocus() {
        if (audioManager == null || focusRequest == null || !focusRequested) return;
        try {
            audioManager.abandonAudioFocusRequest(focusRequest);
        } catch (RuntimeException ignored) {
        }
        focusRequested = false;
    }

    private void pauseForExternal(String reason) {
        if (player == null) return;
        if (player.isPlaying() || player.getPlayWhenReady()) player.pause();
        markPauseReason(reason);
        savePositionQuietly();
        updateForegroundState();
    }

    private void markPauseReason(String reason) {
        getSharedPreferences("dongting_player", MODE_PRIVATE).edit()
                .putString("lastPauseReason", reason)
                .putLong("lastPauseReasonAt", System.currentTimeMillis())
                .apply();
    }

    /** 服务被系统回收前的最后一道保险：把当前进度写回磁盘。 */
    private void savePositionQuietly() {
        if (player == null || player.getCurrentMediaItem() == null) return;
        try {
            if (player.getCurrentMediaItem().localConfiguration == null) return;
            String uri = player.getCurrentMediaItem().localConfiguration.uri.toString();
            long position = player.getCurrentPosition();
            if (uri.isEmpty() || position <= 0) return;
            getSharedPreferences("dongting_player", MODE_PRIVATE).edit()
                    .putLong("pos:" + uri, position)
                    .putString("currentUri", uri)
                    .putLong("posSavedAt:" + uri, System.currentTimeMillis())
                    .apply();
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_TOGGLE.equals(action)) {
            if (player.isPlaying()) {
                player.pause();
                savePositionQuietly();
            } else {
                // 用户显式点击播放，才清除外部暂停标记
                getSharedPreferences("dongting_player", MODE_PRIVATE).edit()
                        .remove("lastPauseReason").apply();
                player.play();
            }
        } else if (ACTION_PREVIOUS.equals(action)) {
            if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem();
        } else if (ACTION_NEXT.equals(action)) {
            if (player.hasNextMediaItem()) player.seekToNextMediaItem();
        } else if (ACTION_STOP.equals(action)) {
            savePositionQuietly();
            player.pause();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        updateForegroundState();
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        savePositionQuietly();
        if (noisyReceiverRegistered) {
            try {
                unregisterReceiver(noisyReceiver);
            } catch (RuntimeException ignored) {
            }
            noisyReceiverRegistered = false;
        }
        abandonPlaybackFocus();
        if (mediaSession != null) mediaSession.release();
        if (player != null) player.release();
        super.onDestroy();
    }

    ExoPlayer getPlayer() {
        return player;
    }

    MediaSession getMediaSession() {
        return mediaSession;
    }

    void refreshNotification() {
        updateForegroundState();
    }

    private void updateForegroundState() {
        if (player == null) return;
        Notification notification = buildNotification();
        // 暂停时也保持前台服务，避免后台听书被系统回收后从头开始
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

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = "地洞播放器";
        String subtitle = player.isPlaying() ? "正在播放" : "已暂停 · 点播放继续";
        if (player.getCurrentMediaItem() != null && player.getCurrentMediaItem().mediaMetadata.title != null) {
            title = String.valueOf(player.getCurrentMediaItem().mediaMetadata.title);
            if (player.getCurrentMediaItem().mediaMetadata.artist != null) {
                subtitle = (player.isPlaying() ? "正在播放 · " : "已暂停 · ") +
                        player.getCurrentMediaItem().mediaMetadata.artist;
            }
        }
        String pauseReason = getSharedPreferences("dongting_player", MODE_PRIVATE)
                .getString("lastPauseReason", "");
        if (!player.isPlaying() && !pauseReason.isEmpty()) {
            subtitle = pauseReason + " · 点播放继续";
        }

        Notification.Builder builder = new Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setSmallIcon(R.drawable.ic_stat_dongting)
                .setColor(UiKit.ACCENT)
                .setContentIntent(contentIntent)
                // 不再用 setDeleteIntent 停服务：划掉通知不会杀死后台播放
                .setOngoing(player.isPlaying())
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_media_previous, "上一首", action(ACTION_PREVIOUS, 1))
                .addAction(player.isPlaying() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        player.isPlaying() ? "暂停" : "播放", action(ACTION_TOGGLE, 2))
                .addAction(android.R.drawable.ic_media_next, "下一首", action(ACTION_NEXT, 3))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", action(ACTION_STOP, 4));
        if (defaultArtwork == null) {
            try {
                defaultArtwork = android.graphics.BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher);
            } catch (Throwable ignored) {
            }
        }
        if (defaultArtwork != null) {
            builder.setLargeIcon(defaultArtwork);
        }
        long duration = player.getDuration();
        long position = player.getCurrentPosition();
        if (duration > 0 && duration != C.TIME_UNSET) {
            builder.setProgress(1000, (int) Math.max(0, Math.min(1000, position * 1000L / duration)), false)
                    .setSubText(MediaUtils.formatMs(position) + " / " + MediaUtils.formatMs(duration));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(CHANNEL_ID);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Token token = mediaSession.getPlatformToken();
            builder.setStyle(new Notification.MediaStyle().setMediaSession(token).setShowActionsInCompactView(0, 1, 2));
            builder.setVisibility(Notification.VISIBILITY_PUBLIC);
        }
        return builder.build();
    }

    private PendingIntent action(String action, int requestCode) {
        Intent intent = new Intent(this, PlaybackService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "地洞播放",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("地洞播放器后台播放控制");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    public class LocalBinder extends Binder {
        PlaybackService getService() {
            return PlaybackService.this;
        }
    }
}
