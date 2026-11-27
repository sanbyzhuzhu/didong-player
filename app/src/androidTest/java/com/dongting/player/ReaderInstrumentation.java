package com.dongting.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/** Dependency-free device regression runner; run only on disposable test emulators. */
public final class ReaderInstrumentation extends Instrumentation {
    private Bundle arguments;
    private Context context;
    private SharedPreferences prefs;
    private String uri;
    private Activity activity;
    private String mode;
    private final String text = "第一句，现在开始逐句跟读。第二句，画面应该同步切换！第三句，暂停以后也能继续。第四句，测试结束。";

    @Override public void onCreate(Bundle args) { arguments = args; start(); }

    @Override public void onStart() {
        if ("wifi".equals(arguments.getString("suite"))) {
            new WifiTransferInstrumentation(this).run();
            return;
        }
        if ("webdav".equals(arguments.getString("suite"))) {
            new WebDavInstrumentation(this).run();
            return;
        }
        if ("updates-live".equals(arguments.getString("suite"))) {
            new UpdateInstrumentation(this).runLive();
            return;
        }
        if ("updates".equals(arguments.getString("suite"))) {
            new UpdateInstrumentation(this).run();
            return;
        }
        if ("books".equals(arguments.getString("suite"))) {
            new BookInstrumentation(this).onStart();
            return;
        }
        Bundle result = new Bundle();
        try {
            context = getTargetContext();
            prefs = context.getSharedPreferences("dongting_player", Context.MODE_PRIVATE);
            mode = arguments.getString("provider", "system");
            File file = new File(context.getFilesDir(), "sentence-reader-test.txt");
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
            }
            uri = Uri.fromFile(file).toString();
            prefs.edit().putString("lastTextUri", uri).putString("textBooks", "[]")
                    .putString("ttsProvider", mode).putString("ttsVoice", "")
                    .putString("externalTtsForwardUrl", arguments.getString("endpoint", "http://10.0.2.2:18774/forward"))
                    .putInt("ttsChunk:" + uri, 0).putInt("ttsSentenceStart:" + uri, 0)
                    .putString("ttsVisualMode:" + uri, "sentence").commit();
            activity = startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            command(TextReaderService.ACTION_START);
            waitFor(() -> prefs.getInt("ttsSentenceEnd:" + uri, 0) > 0 && TextReaderService.isRunning(), "first sentence");
            command(TextReaderService.ACTION_TOGGLE);
            waitFor(TextReaderService::isPaused, "pause first sentence");
            SystemClock.sleep(800);
            assertVisual("▶ 第一句，现在开始逐句跟读。");
            int saved = prefs.getInt("ttsSentenceStart:" + uri, -1);
            SystemClock.sleep(1600);
            check(prefs.getInt("ttsSentenceStart:" + uri, -2) == saved, "Paused reading advanced");
            command(TextReaderService.ACTION_TOGGLE);
            int second = text.indexOf("第二句");
            waitFor(() -> prefs.getInt("ttsSentenceStart:" + uri, -1) == second, "second sentence");
            command(TextReaderService.ACTION_TOGGLE);
            waitFor(TextReaderService::isPaused, "pause second sentence");
            activity = startActivitySync(new Intent(context, NowPlayingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(800);
            assertVisual("▶ 第二句，画面应该同步切换！");
            check(prefs.getInt("ttsChunk:" + uri, -1) == 0, "Paragraph index changed inside a paragraph");
            check(!TextReaderService.isRangeTrackingSupported(), "Test must exercise no-word-callback fallback");
            Bitmap image = getUiAutomation().takeScreenshot();
            try (FileOutputStream out = new FileOutputStream(new File(context.getExternalFilesDir(null), "reader-" + mode + ".png"))) {
                image.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            command(TextReaderService.ACTION_STOP);
            waitFor(() -> !TextReaderService.isRunning(), "stop service");
            command(TextReaderService.ACTION_START);
            waitFor(() -> TextReaderService.isRunning()
                    && prefs.getInt("ttsSentenceStart:" + uri, -1) == second, "restore saved sentence");
            // Let initialization complete before pausing the restarted system engine.
            SystemClock.sleep(400);
            command(TextReaderService.ACTION_TOGGLE);
            waitFor(TextReaderService::isPaused, "pause restored sentence");
            command(TextReaderService.ACTION_TOGGLE);
            int third = text.indexOf("第三句");
            waitFor(() -> prefs.getInt("ttsSentenceStart:" + uri, -1) == third, "third sentence after resume");
            command(TextReaderService.ACTION_SEEK_CHUNK);
            waitFor(() -> prefs.getInt("ttsSentenceStart:" + uri, -1) == 0, "restart paragraph");
            SystemClock.sleep(300);
            check(prefs.getInt("ttsSentenceStart:" + uri, -1) == 0, "Stale callback skipped the new sentence");
            waitFor(() -> prefs.getInt("ttsSentenceStart:" + uri, -1) == second, "second sentence after seek");
            result.putString("stream", "PASS: " + mode + " sentence advance, both screens, pause/resume, saved sentence restore, stable paragraph indices and seek callbacks\n");
            command(TextReaderService.ACTION_STOP);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error));
            if (context != null) runOnMainSync(() -> context.stopService(new Intent(context, TextReaderService.class)));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void command(String action) {
        runOnMainSync(() -> {
            Intent intent = new Intent(context, TextReaderService.class).setAction(action);
            if (TextReaderService.ACTION_START.equals(action)) intent.putExtra(TextReaderService.EXTRA_TEXT_URI, uri);
            if (TextReaderService.ACTION_SEEK_CHUNK.equals(action)) intent.putExtra(TextReaderService.EXTRA_CHUNK_INDEX, 0);
            context.startService(intent);
        });
    }

    private void waitFor(BooleanSupplier condition, String label) {
        long deadline = SystemClock.uptimeMillis() + 18000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25);
        check(condition.getAsBoolean(), "Timed out: " + label + "; offset="
                + prefs.getInt("ttsSentenceStart:" + uri, -1) + "; error=" + prefs.getString("lastExternalTtsError", ""));
    }

    private void assertVisual(String expected) {
        final String[] body = {""};
        runOnMainSync(() -> body[0] = visualText(activity.getWindow().getDecorView()));
        check(body[0].contains(expected), "Wrong visible sentence: " + body[0]);
    }

    private String visualText(View view) {
        if (view instanceof DongtingVisualView) return String.valueOf(view.getContentDescription());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                String found = visualText(group.getChildAt(i));
                if (!found.isEmpty()) return found;
            }
        }
        return "";
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
