package com.dongting.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.media3.common.MediaItem;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Mock only update metadata; exercise the actual activity and real audio playback. */
public final class UpdateInstrumentation {
    private final Instrumentation runner;
    private Context context;
    private SharedPreferences prefs;
    private Activity activity;
    private volatile PlaybackService playback;
    private Instrumentation.ActivityMonitor browser;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            playback = ((PlaybackService.LocalBinder) service).getService();
        }
        @Override public void onServiceDisconnected(ComponentName name) { playback = null; }
    };

    UpdateInstrumentation(Instrumentation runner) { this.runner = runner; }

    public void runLive() {
        Bundle result = new Bundle();
        try {
            context = runner.getTargetContext();
            prefs = AppUpdates.preferences(context);
            check(AppUpdates.checkInBackground(context, true), "Start real public update check");
            waitFor(() -> !AppUpdates.isChecking() && prefs.getLong(AppUpdates.FINISHED, 0) > 0, "real GitHub/Gitee check");
            check(prefs.getBoolean(AppUpdates.SUCCEEDED, false), "Both public update sources unavailable from emulator");
            UpdateInfo info = AppUpdates.cached(context);
            check(info != null && info.versionCode == AppUpdates.installedCode(context), "Published feed does not match installed release");
            result.putString("stream", "PASS: real public update feed read and validated via "
                    + prefs.getString(AppUpdates.SOURCE, "") + "; installed V" + info.versionName + " is current\n");
            runner.finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error));
            runner.finish(Activity.RESULT_CANCELED, result);
        }
    }

    public void run() {
        Bundle result = new Bundle();
        try {
            context = runner.getTargetContext();
            prefs = AppUpdates.preferences(context);
            prefs.edit().clear().putLong(AppUpdates.ATTEMPT, System.currentTimeMillis())
                    .putBoolean(AppUpdates.SUCCEEDED, true).commit();
            context.getSharedPreferences("dongting_player", Context.MODE_PRIVATE).edit()
                    .putLong("pos:update-regression", 123456).commit();
            android.util.Log.i("UpdateTest", "Launching main");
            runner.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            android.util.Log.i("UpdateTest", "Binding player");
            runner.runOnMainSync(() -> context.bindService(new Intent(context, PlaybackService.class), connection, Context.BIND_AUTO_CREATE));
            waitFor(() -> playback != null, "playback service");
            android.util.Log.i("UpdateTest", "Player bound");
            File wav = new File(context.getFilesDir(), "update-test-audio.wav");
            byte[] audio = new byte[44 + 16000 * 2 * 90];
            ByteBuffer buffer = ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN);
            buffer.put(new byte[]{'R','I','F','F'}).putInt(audio.length - 8).put(new byte[]{'W','A','V','E','f','m','t',' '})
                    .putInt(16).putShort((short)1).putShort((short)1).putInt(16000).putInt(32000)
                    .putShort((short)2).putShort((short)16).put(new byte[]{'d','a','t','a'}).putInt(audio.length - 44);
            try (FileOutputStream output = new FileOutputStream(wav)) { output.write(audio); }
            runner.runOnMainSync(() -> {
                playback.getPlayer().setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(wav)));
                playback.getPlayer().prepare();
                playback.getPlayer().play();
            });
            waitFor(this::isPlaying, "real audio playback");
            android.util.Log.i("UpdateTest", "Audio playing; launching settings");
            IntentFilter browserFilter = new IntentFilter(Intent.ACTION_VIEW);
            browserFilter.addDataScheme("https");
            browser = runner.addMonitor(browserFilter, new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
            activity = runner.startActivitySync(new Intent(context, SettingsActivity.class).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            scrollBottom();
            android.util.Log.i("UpdateTest", "Settings open");
            check(!dot(), "No red dot without an update");

            final long installed = AppUpdates.installedCode(context);
            long future = installed + 1;
            check(AppUpdates.checkInBackground(context, true, url -> manifest(future)), "Start mocked check");
            waitFor(() -> !AppUpdates.isChecking() && dot(), "new-version dot");
            assertPassive();
            SystemClock.sleep(600);
            screenshot("update-red-dot.png");

            clickEntry();
            clickDialog("忽略此版本");
            waitFor(() -> !dot(), "ignore clears dot");
            check(prefs.getLong(AppUpdates.IGNORED, 0) == future, "Persist ignored code");
            runner.runOnMainSync(() -> activity.finish());
            activity = runner.startActivitySync(new Intent(context, SettingsActivity.class).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            scrollBottom();
            check(!dot(), "Ignored release must remain ignored after reopen");

            AtomicInteger calls = new AtomicInteger();
            check(!AppUpdates.checkInBackground(context, false, url -> { calls.incrementAndGet(); return manifest(future); }), "Cooldown must skip automatic checks");
            check(calls.get() == 0, "No network request inside cooldown");
            AppUpdates.checkInBackground(context, true, url -> manifest(future));
            waitFor(() -> !AppUpdates.isChecking(), "manual ignored check");
            check(!dot(), "Manual check must not undo ignore");

            AppUpdates.checkInBackground(context, true, url -> manifest(future + 1));
            waitFor(() -> !AppUpdates.isChecking() && dot(), "higher version after ignore");
            assertPassive();
            AtomicInteger failed = new AtomicInteger();
            AppUpdates.checkInBackground(context, true, url -> { failed.incrementAndGet(); throw new IOException("offline"); });
            waitFor(() -> !AppUpdates.isChecking() && !prefs.getBoolean(AppUpdates.SUCCEEDED, true), "offline result");
            check(failed.get() == 2 && dot(), "Try both sources and preserve cached update on failure");
            assertPassive();

            AtomicInteger mirrors = new AtomicInteger();
            AppUpdates.checkInBackground(context, true, url -> {
                mirrors.incrementAndGet();
                if (url.equals(AppUpdates.GITHUB)) return "<html>gateway unavailable</html>";
                return new JSONObject().put("encoding", "base64").put("size", manifest(future + 2).length())
                        .put("content", android.util.Base64.encodeToString(manifest(future + 2).getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP)).toString();
            });
            waitFor(() -> !AppUpdates.isChecking() && "Gitee".equals(prefs.getString(AppUpdates.SOURCE, "")), "Gitee fallback");
            check(mirrors.get() == 2 && dot(), "Mirror check");
            assertPassive();

            clickEntry();
            clickDialog("更新");
            check(browser.getHits() == 0, "First update click must not open browser");
            clickDialog("取消");
            check(browser.getHits() == 0 && isPlaying(), "Cancel update must preserve playback and current version");
            clickEntry();
            clickDialog("更新");
            clickDialog("确认前往下载");
            waitFor(() -> browser.getHits() == 1, "explicit confirmed browser navigation");
            check(AppUpdates.installedCode(context) == installed, "Never replace installed version");
            check(context.getSharedPreferences("dongting_player", Context.MODE_PRIVATE).getLong("pos:update-regression", 0) == 123456,
                    "Update checker changed stored playback data");
            check(isPlaying(), "Update UI interrupted audio");

            runner.runOnMainSync(() -> playback.getPlayer().stop());
            runner.removeMonitor(browser);
            result.putString("stream", "PASS: silent red dot only in settings, no automatic dialogs/browser/install, persistent ignore, higher-version prompt, offline cache, Gitee fallback, manual cooldown and second confirmation; real audio playback/data unaffected\n");
            runner.finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            try { screenshot("update-test-failure.png"); } catch (Throwable ignored) { }
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error));
            runner.finish(Activity.RESULT_CANCELED, result);
        }
    }

    private String manifest(long code) throws Exception {
        String version = "0.0." + code;
        return new JSONObject().put("schemaVersion", 1).put("versionCode", code).put("versionName", version)
                .put("releaseNotes", "测试用新版：仅显示红点，不自动更新。")
                .put("releaseUrl", "https://github.com/sanbyzhuzhu/didong-player/releases/tag/v" + version)
                .put("mirrorUrl", "https://gitee.com/sanbyzhu/didong-player").toString();
    }
    private boolean isPlaying() {
        final boolean[] playing = {false};
        runner.runOnMainSync(() -> playing[0] = playback != null && playback.getPlayer().isPlaying());
        return playing[0];
    }
    private View view(String tag) { return activity.getWindow().getDecorView().findViewWithTag(tag); }
    private boolean dot() {
        final boolean[] visible = {false};
        runner.runOnMainSync(() -> visible[0] = view("app-update-dot").getVisibility() == View.VISIBLE);
        return visible[0];
    }
    private void clickEntry() { runner.runOnMainSync(() -> view("app-update-entry").performClick()); }
    private void scrollBottom() {
        runner.runOnMainSync(() -> {
            ScrollView scroll = scroll(activity.getWindow().getDecorView());
            if (scroll != null) scroll.fullScroll(View.FOCUS_DOWN);
        });
        SystemClock.sleep(450);
    }
    private ScrollView scroll(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { ScrollView found = scroll(group.getChildAt(i)); if (found != null) return found; }
        }
        return null;
    }
    private void assertPassive() {
        final boolean[] focused = {false};
        runner.runOnMainSync(() -> focused[0] = activity.hasWindowFocus());
        check(focused[0] && browser.getHits() == 0 && isPlaying(), "Automatic check opened UI or interrupted playback");
    }
    private void clickDialog(String text) {
        waitFor(() -> {
            AccessibilityNodeInfo root = runner.getUiAutomation().getRootInActiveWindow();
            AccessibilityNodeInfo node = find(root, text);
            if (node == null) return false;
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }, "click " + text);
    }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String text) {
        if (node == null) return null;
        if (node.getText() != null && node.getText().toString().equals(text)) return node;
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo found = find(node.getChild(i), text); if (found != null) return found; }
        return null;
    }
    private void screenshot(String name) throws Exception {
        Bitmap image = runner.getUiAutomation().takeScreenshot();
        try (FileOutputStream out = new FileOutputStream(new File(context.getExternalFilesDir(null), name))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
    private void waitFor(BooleanSupplier predicate, String label) {
        long end = SystemClock.uptimeMillis() + 20000;
        while (!predicate.getAsBoolean()) { check(SystemClock.uptimeMillis() < end, "Timeout: " + label); SystemClock.sleep(30); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
