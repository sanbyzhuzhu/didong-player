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
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Actual Android XML/HTML decoding, both reader screens, and clickable EPUB anchors. */
public final class BookInstrumentation {
    private final Instrumentation runner;
    BookInstrumentation(Instrumentation runner) { this.runner = runner; }
    private Context getTargetContext() { return runner.getTargetContext(); }
    private void runOnMainSync(Runnable runnable) { runner.runOnMainSync(runnable); }
    private Activity startActivitySync(Intent intent) { return runner.startActivitySync(intent); }
    private android.app.UiAutomation getUiAutomation() { return runner.getUiAutomation(); }
    private void finish(int code, Bundle result) { runner.finish(code, result); }
    private Context context;
    private SharedPreferences prefs;
    private Activity activity;
    private Uri current;
    private final Bundle result = new Bundle();

    public void onStart() {
        try {
            context = getTargetContext();
            prefs = context.getSharedPreferences("dongting_player", Context.MODE_PRIVATE);
            prefs.edit().putString("ttsProvider", "system").putString("ttsVoice", "").putString("textBooks", "[]").commit();
            current = fixture("epub3.epub");
            prefs.edit().putString("lastTextUri", current.toString()).commit();
            activity = startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            for (String name : new String[]{"epub3.epub", "epub2.epub", "sample.docx", "sample.md", "sample.html"}) {
                current = fixture(name);
                ReadableBook book = BookLoader.load(context, current);
                check(!book.chapters.isEmpty(), "Missing chapters: " + name);
                check(!book.text.contains("<w:") && !book.text.contains("<html") && !book.text.contains("**"), "Markup read aloud: " + name);
                if (name.endsWith(".epub")) check(book.text.startsWith("第一章\n"), "ZIP order leaked: " + book.text);
                if (name.endsWith(".html")) check(book.text.contains("& 中文"), "Android HTML entity decoding");
                final Uri target = current;
                runOnMainSync(() -> invoke(activity, "openTextUri", new Class<?>[]{Uri.class, String.class}, target, "测试导入："));
                // All three surfaces use exactly the same extracted text.
                final String[] mainText = {""};
                runOnMainSync(() -> mainText[0] = (String) invoke(activity, "readText", new Class<?>[]{Uri.class}, target));
                check(book.text.equals(mainText[0]), "Main reader extraction differs: " + name);
                command(TextReaderService.ACTION_START, 0, 0);
                waitFor(() -> TextReaderService.isReading(target.toString())
                        && prefs.getInt("ttsSentenceEnd:" + target, 0) > 0, "read " + name);
                command(TextReaderService.ACTION_TOGGLE, 0, 0);
                waitFor(TextReaderService::isPaused, "pause " + name);
                SystemClock.sleep(650);
                check(visualText().contains("逐句跟读"), "No synchronized text: " + name + ": " + visualText());
            }

            current = fixture("epub3.epub");
            runOnMainSync(() -> invoke(activity, "openTextUri", new Class<?>[]{Uri.class, String.class}, current, "测试导入："));
            ReadableBook epub = BookLoader.load(context, current);
            List<String> chunks = TtsText.splitChunks(epub.text);
            TtsText.Position sub = TtsText.positionForOffset(epub.text, chunks, epub.chapters.get(1).offset);
            check(sub.offset > 0, "Fixture must cover same-file fragment navigation");
            runOnMainSync(() -> invoke(activity, "showTextChaptersDialog", new Class<?>[0]));
            clickText("目录小节");
            waitFor(() -> TextReaderService.isReading(current.toString())
                    && prefs.getInt("ttsSentenceStart:" + current, -1) == sub.offset, "main TOC anchor");
            command(TextReaderService.ACTION_TOGGLE, 0, 0);
            waitFor(TextReaderService::isPaused, "pause chapter");
            SystemClock.sleep(650);
            check(visualText().contains("▶ 第一章的小节"), "Wrong highlighted chapter after TOC jump: " + visualText());
            activity = startActivitySync(new Intent(context, NowPlayingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(650);
            final String[] playerText = {""};
            runOnMainSync(() -> playerText[0] = (String) invoke(activity, "readText", new Class<?>[]{Uri.class}, current));
            check(epub.text.equals(playerText[0]), "Player extraction differs");
            runOnMainSync(() -> invoke(activity, "showReadingCatalogDialog", new Class<?>[0]));
            clickText("目录第二章");
            TtsText.Position second = TtsText.positionForOffset(epub.text, chunks, epub.chapters.get(2).offset);
            waitFor(() -> prefs.getInt("ttsSentenceStart:" + current, -1) == second.offset, "player TOC anchor");
            command(TextReaderService.ACTION_TOGGLE, 0, 0);
            waitFor(TextReaderService::isPaused, "pause second chapter");
            SystemClock.sleep(650);
            check(visualText().contains("▶ 第二章"), "Player did not follow chapter jump: " + visualText());
            Bitmap screenshot = getUiAutomation().takeScreenshot();
            try (FileOutputStream out = new FileOutputStream(new File(context.getExternalFilesDir(null), "book-catalog.png"))) {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            command(TextReaderService.ACTION_STOP, 0, 0);
            waitFor(() -> !TextReaderService.isRunning(), "stop before migration");
            Uri legacy = fixture("legacy.epub");
            ReadableBook raw = new BookTextParser(MediaUtils::decodeTextBytes,
                    value -> android.text.Html.fromHtml(value, android.text.Html.FROM_HTML_MODE_LEGACY).toString())
                    .parse(context.getContentResolver().openInputStream(legacy), "legacy.epub", "", true);
            int oldOffset = TtsText.splitChunks(raw.legacyText).get(0).indexOf("第二章正文");
            prefs.edit().remove("bookLayout2:" + legacy).putInt("ttsChunk:" + legacy, 0)
                    .putInt("ttsSentenceStart:" + legacy, oldOffset).putString("textMarks:" + legacy, "[0]").commit();
            ReadableBook migrated = BookLoader.load(context, legacy);
            int migratedChunk = prefs.getInt("ttsChunk:" + legacy, -1);
            int migratedOffset = prefs.getInt("ttsSentenceStart:" + legacy, -1);
            check(TtsText.splitChunks(migrated.text).get(migratedChunk).substring(migratedOffset).startsWith("第二章正文"), "Saved EPUB progress did not migrate");
            check(prefs.contains("legacyEpubChunk:" + legacy), "Original progress backup missing");
            result.putString("stream", "PASS: Android imports EPUB2/3, DOCX, Markdown, HTML; shared reading text; main/player TOC clicks to same-file anchors; synchronized highlights; legacy progress migration\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            try {
                Bitmap screenshot = getUiAutomation().takeScreenshot();
                try (FileOutputStream out = new FileOutputStream(new File(context.getExternalFilesDir(null), "book-catalog-failure.png"))) {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                android.util.Log.e("BookTest", "Visible nodes: " + nodeText(getUiAutomation().getRootInActiveWindow()));
            } catch (Throwable ignored) { }
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error));
            if (context != null) runOnMainSync(() -> context.stopService(new Intent(context, TextReaderService.class)));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private Uri fixture(String name) { return Uri.fromFile(new File(context.getFilesDir(), "book-test-fixtures/" + name)); }
    private void command(String action, int chunk, int offset) {
        runOnMainSync(() -> context.startService(new Intent(context, TextReaderService.class).setAction(action)
                .putExtra(TextReaderService.EXTRA_TEXT_URI, current.toString())
                .putExtra(TextReaderService.EXTRA_CHUNK_INDEX, chunk)
                .putExtra(TextReaderService.EXTRA_SENTENCE_OFFSET, offset)));
    }
    private void waitFor(BooleanSupplier condition, String label) {
        long end = SystemClock.uptimeMillis() + 20000;
        while (!condition.getAsBoolean()) {
            check(SystemClock.uptimeMillis() < end, "Timeout: " + label);
            SystemClock.sleep(25);
        }
    }
    private void clickText(String text) {
        android.app.UiAutomation ui = getUiAutomation();
        android.accessibilityservice.AccessibilityServiceInfo info = ui.getServiceInfo();
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                | android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        ui.setServiceInfo(info);
        try { ui.waitForIdle(100, 1500); } catch (java.util.concurrent.TimeoutException ignored) { }
        final boolean[] clicked = {false};
        waitFor(() -> {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root == null) return false;
            AccessibilityNodeInfo node = findText(root, text);
            if (node == null) return false;
            if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                android.graphics.Rect bounds = new android.graphics.Rect();
                node.getBoundsInScreen(bounds);
                if (bounds.isEmpty()) return false;
                long now = SystemClock.uptimeMillis();
                runner.sendPointerSync(android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN,
                        bounds.centerX(), bounds.centerY(), 0));
                runner.sendPointerSync(android.view.MotionEvent.obtain(now, now + 50, android.view.MotionEvent.ACTION_UP,
                        bounds.centerX(), bounds.centerY(), 0));
            }
            clicked[0] = true;
            return true;
        }, "click " + text);
        check(clicked[0], "Could not click chapter");
    }
    private AccessibilityNodeInfo findText(AccessibilityNodeInfo node, String wanted) {
        if (node == null) return null;
        if (node.getText() != null && node.getText().toString().contains(wanted)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findText(node.getChild(i), wanted);
            if (found != null) return found;
        }
        return null;
    }
    private String nodeText(AccessibilityNodeInfo node) {
        if (node == null) return "<no root>";
        StringBuilder out = new StringBuilder(String.valueOf(node.getText())).append(" / ").append(node.getContentDescription()).append('\n');
        for (int i = 0; i < node.getChildCount(); i++) out.append(nodeText(node.getChild(i)));
        return out.toString();
    }
    private String visualText() {
        final String[] value = {""};
        runOnMainSync(() -> value[0] = findVisual(activity.getWindow().getDecorView()));
        return value[0];
    }
    private String findVisual(View view) {
        if (view instanceof DongtingVisualView) return String.valueOf(view.getContentDescription());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                String value = findVisual(group.getChildAt(i));
                if (!value.isEmpty()) return value;
            }
        }
        return "";
    }
    private static Object invoke(Object object, String name, Class<?>[] parameters, Object... args) {
        try {
            Method method = object.getClass().getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method.invoke(object, args);
        } catch (Exception error) { throw new AssertionError(error); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
