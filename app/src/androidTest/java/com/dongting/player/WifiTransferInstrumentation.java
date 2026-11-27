package com.dongting.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class WifiTransferInstrumentation {
    private final Instrumentation runner;
    private Activity wifi, main;
    WifiTransferInstrumentation(Instrumentation runner) { this.runner = runner; }
    void run() {
        Bundle result = new Bundle();
        try {
            Context context = runner.getTargetContext();
            wifi = runner.startActivitySync(new Intent(context, WifiTransferActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            check(field(wifi, "server") == null, "Transfer must not start automatically");
            runner.runOnMainSync(() -> invoke(wifi, "startServer", new Class<?>[0]));
            WifiTransferServer server = (WifiTransferServer) field(wifi, "server");
            check(server != null, "Wi-Fi interface discovery and manual server startup");
            String base = "http://127.0.0.1:" + server.port() + server.path();
            check(get(base).contains("Wi-Fi 传输"), "Browser asset served on device");
            byte[] book = "无线传输第一句。第二句可以离线朗读。".getBytes(StandardCharsets.UTF_8);
            check(upload(base, "无线传输 书籍.txt", book) == 201, "Receive Chinese book");
            check(upload(base, "无线传输 书籍.txt", book) == 201, "Receive duplicate name safely");
            check(upload(base, "无线音频.wav", wav()) == 201, "Receive audio");
            check(WifiTransferServer.files(WifiTransferActivity.root(context)).size() >= 3, "Persistent private files");
            String oldPath = server.path();
            runner.runOnMainSync(() -> invoke(wifi, "stopServer", new Class<?>[0]));
            check(!reachable(base), "Manual stop closes listener");
            runner.runOnMainSync(() -> invoke(wifi, "startServer", new Class<?>[0]));
            server = (WifiTransferServer) field(wifi, "server");
            check(server != null && !server.path().equals(oldPath), "Restart rotates access key");
            String latest = "http://127.0.0.1:" + server.port() + server.path();
            // Wait for all upload-completion UI updates before taking a screenshot.
            runner.waitForIdleSync();
            Bitmap image = runner.getUiAutomation().takeScreenshot();
            try (FileOutputStream output = new FileOutputStream(new File(context.getExternalFilesDir(null), "wifi-transfer.png"))) {
                image.compress(Bitmap.CompressFormat.PNG, 100, output);
            }
            Instrumentation.ActivityMonitor monitor = runner.addMonitor(MainActivity.class.getName(), null, false);
            runner.runOnMainSync(() -> invoke(wifi, "finishWithReceived", new Class<?>[0]));
            main = monitor.waitForActivityWithTimeout(15000); runner.removeMonitor(monitor);
            check(main != null, "Transfer returns received files to main activity");
            waitFor(() -> field(main, "player") != null, "Playback service connected");
            waitFor(() -> {
                AtomicBoolean ready = new AtomicBoolean();
                runner.runOnMainSync(() -> ready.set(((ExoPlayer) field(main, "player")).getPlaybackState() == Player.STATE_READY));
                return ready.get();
            }, "Received media is playable");
            check(!reachable(latest), "Leaving page closes transfer listener");
            String shelf = context.getSharedPreferences("dongting_player", Context.MODE_PRIVATE).getString("textBooks", "");
            check(shelf.contains("无线传输 书籍.txt"), "Received book is in shelf");
            List<File> files = WifiTransferServer.files(WifiTransferActivity.root(context));
            File firstBook = null; for (File file : files) if (file.getName().endsWith(".txt")) { firstBook = file; break; }
            check(firstBook != null && BookLoader.load(context, Uri.fromFile(firstBook)).text.contains("无线传输第一句"), "Received book parses offline");
            ArrayList<String> uris = new ArrayList<>(); uris.add(Uri.fromFile(firstBook).toString());
            runner.runOnMainSync(() -> invoke(main, "importNetworkFiles", new Class<?>[]{Intent.class, boolean.class},
                    new Intent().putStringArrayListExtra(WifiTransferActivity.EXTRA_URIS, uris), true));
            check(((String) field(main, "importedText")).contains("无线传输第一句"), "Reader opens received book");
            runner.runOnMainSync(() -> ((ExoPlayer) field(main, "player")).pause());
            result.putString("stream", "PASS: manual Wi-Fi startup, device HTTP page/upload, Chinese and duplicate files, stop/restart key rotation, page exit shutdown, main import, media playback and offline reader\n");
            runner.finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error)); runner.finish(0, result);
        } finally {
            if (wifi != null) runner.runOnMainSync(() -> wifi.finish());
            if (main != null) runner.runOnMainSync(() -> main.finish());
        }
    }
    private static byte[] wav() {
        int length = 16000 * 2 * 3;
        ByteBuffer b = ByteBuffer.allocate(length + 44).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(length + 36).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII))
                .putInt(16).putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000).putShort((short) 2)
                .putShort((short) 16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(length);
        return b.array();
    }
    private static int upload(String base, String name, byte[] bytes) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + "upload?name=" + URLEncoder.encode(name, "UTF-8")).openConnection();
        c.setConnectTimeout(3000); c.setReadTimeout(5000); c.setRequestMethod("PUT"); c.setDoOutput(true); c.setFixedLengthStreamingMode(bytes.length);
        try { try (OutputStream output = c.getOutputStream()) { output.write(bytes); } return c.getResponseCode(); }
        finally { c.disconnect(); }
    }
    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(); c.setConnectTimeout(1000); c.setReadTimeout(2000);
        try (java.io.InputStream input = c.getInputStream(); java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192]; for (int n; (n = input.read(bytes)) != -1;) output.write(bytes, 0, n);
            return output.toString("UTF-8");
        } finally { c.disconnect(); }
    }
    private static boolean reachable(String url) { try { get(url); return true; } catch (Exception expected) { return false; } }
    private static Object field(Object target, String name) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
    private static void invoke(Object target, String name, Class<?>[] types, Object... args) {
        try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(target, args); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
    private static void waitFor(BooleanSupplier condition, String label) {
        long deadline = SystemClock.elapsedRealtime() + 20000;
        while (SystemClock.elapsedRealtime() < deadline) { if (condition.getAsBoolean()) return; SystemClock.sleep(100); }
        throw new AssertionError("Timeout: " + label);
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
