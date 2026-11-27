package com.dongting.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class WebDavInstrumentation {
    private final Instrumentation runner;
    private Activity activity;
    private Context context;
    WebDavInstrumentation(Instrumentation runner) { this.runner = runner; }

    void run() {
        Bundle result = new Bundle();
        try {
            context = runner.getTargetContext();
            context.getSharedPreferences("dongting_player", Context.MODE_PRIVATE).edit().putString("textBooks", "[]").commit();
            WebDavAccount.delete(context);
            WebDavAccount account = new WebDavAccount("http://127.0.0.1:18776/dav", "reader", "test-webdav-secret");
            check(account.root.toString().endsWith("/dav/"), "Normalize trailing slash");
            check(!account.contains(account.root.resolve("../public/file.wav")), "Sibling path rejected");
            check(!account.contains(account.root.resolve("https://127.0.0.1:18776/dav/file.wav")), "Protocol change rejected");
            check(!account.contains(account.root.resolve("http://other.example/dav/file.wav")), "Foreign host rejected");
            account.save(context);
            WebDavAccount restored = WebDavAccount.load(context);
            check(restored != null && restored.password.equals(account.password) && restored.root.equals(account.root), "Keystore round trip");
            byte[] encrypted = Files.readAllBytes(new File(context.getNoBackupFilesDir(), "webdav-account.enc").toPath());
            check(!new String(encrypted, StandardCharsets.ISO_8859_1).contains(account.password), "No plaintext password");
            check(!context.getSharedPreferences("dongting_player", Context.MODE_PRIVATE).getAll().containsValue(account.password), "No exported password");
            WebDavClient client = new WebDavClient(restored);
            List<WebDavClient.Entry> entries = client.list(account.root);
            check(entries.size() == 4 && entries.get(0).directory, "Depth 1 directory and supported entries");
            WebDavClient.Entry book = find(entries, "云端 书籍.txt");
            check(BookTextParser.supportedName(book.name), "Chinese percent-encoded name");
            File downloaded = client.download(context, book);
            check(downloaded.isFile() && downloaded.getCanonicalPath().startsWith(context.getNoBackupFilesDir().getCanonicalPath()), "Private persistent download");
            check(BookLoader.load(context, Uri.fromFile(downloaded)).text.contains("云端朗读第一句"), "Book parser reads downloaded text");
            check(client.list(find(entries, "子目录").url).size() == 1, "Nested directory listing");
            boolean rejected = false;
            try { new WebDavClient(new WebDavAccount(account.root.toString(), "reader", "wrong")).list(account.root); }
            catch (java.io.IOException expected) { rejected = expected.getMessage().contains("401"); }
            check(rejected, "Wrong password reported");
            rejected = false;
            try { client.list(account.root.resolve("outside/")); }
            catch (java.io.IOException expected) { rejected = true; }
            check(rejected, "Out-of-root DAV redirect rejected");
            WebDavClient.Entry flaky = new WebDavClient.Entry(account.root.resolve("flaky.txt"), "flaky.txt", false, -1);
            File stable = client.download(context, flaky);
            byte[] before = Files.readAllBytes(stable.toPath());
            rejected = false;
            try { client.download(context, flaky); } catch (Exception expected) { rejected = true; }
            check(rejected && java.util.Arrays.equals(before, Files.readAllBytes(stable.toPath())), "Interrupted download preserves old file");
            check(!new File(stable.getParentFile(), "download.part").exists(), "Partial file removed");
            WebDavClient cancelled = new WebDavClient(account);
            cancelled.cancel(); rejected = false;
            try { cancelled.download(context, book); } catch (java.io.IOException expected) { rejected = true; }
            check(rejected, "Cancelled download does not start");
            String xml = "<d:multistatus xmlns:d='DAV:'>"
                    + response("/dav/", 200) + response("/dav/no.txt", 403)
                    + response("http://evil.example/dav/no.txt", 200) + response("/dav2/no.txt", 200)
                    + response("/dav/sub/no.txt", 200) + response("/dav/%2e%2e/no.txt", 200)
                    + response("/dav/yes.txt", 200) + response("/dav/yes.txt", 200) + "</d:multistatus>";
            List<WebDavClient.Entry> parsed = WebDavClient.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), account.root, account);
            check(parsed.size() == 1 && parsed.get(0).name.equals("yes.txt"), "DAV filters self, failed propstat, duplicate, traversal and foreign children");
            rejected = false;
            try {
                String unsafe = "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><d:multistatus xmlns:d='DAV:'/>";
                WebDavClient.parse(new ByteArrayInputStream(unsafe.getBytes(StandardCharsets.UTF_8)), account.root, account);
            } catch (Exception expected) { rejected = true; }
            check(rejected, "DOCTYPE and external entities rejected");

            activity = runner.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitFor(() -> field(activity, "player") != null, "Main playback service connection");
            ArrayList<String> books = new ArrayList<>(); books.add(Uri.fromFile(downloaded).toString());
            runner.runOnMainSync(() -> invoke(activity, "importWebDavFiles", new Class<?>[]{Intent.class},
                    new Intent().putStringArrayListExtra(WebDavActivity.EXTRA_URIS, books)));
            check(((String) field(activity, "importedText")).contains("云端朗读第一句"), "Downloaded book imports into main reader");
            ArrayList<String> bulkBooks = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                File bookFile = new File(downloaded.getParentFile(), "batch-" + i + ".txt");
                Files.write(bookFile.toPath(), "批量书籍正文。".getBytes(StandardCharsets.UTF_8));
                bulkBooks.add(Uri.fromFile(bookFile).toString());
            }
            runner.runOnMainSync(() -> invoke(activity, "importWebDavFiles", new Class<?>[]{Intent.class},
                    new Intent().putStringArrayListExtra(WebDavActivity.EXTRA_URIS, bulkBooks)));
            check(new org.json.JSONArray(context.getSharedPreferences("dongting_player", Context.MODE_PRIVATE)
                    .getString("textBooks", "[]")).length() == 61, "Bulk import keeps more than 50 books and preserves existing books");
            for (String name : new String[]{"sample.wav", "redirect.wav"}) {
                ArrayList<String> media = new ArrayList<>(); media.add(account.root.resolve(name).toString());
                runner.runOnMainSync(() -> invoke(activity, "importWebDavFiles", new Class<?>[]{Intent.class},
                        new Intent().putStringArrayListExtra(WebDavActivity.EXTRA_URIS, media)));
                waitFor(() -> {
                    AtomicBoolean ready = new AtomicBoolean();
                    runner.runOnMainSync(() -> ready.set(((ExoPlayer) field(activity, "player")).getPlaybackState() == Player.STATE_READY));
                    return ready.get();
                }, "Authenticated stream " + name);
                runner.runOnMainSync(() -> ((ExoPlayer) field(activity, "player")).seekTo(120000));
                SystemClock.sleep(400);
            }
            runner.runOnMainSync(() -> ((ExoPlayer) field(activity, "player")).pause());
            WebDavAccount.delete(context);
            check(WebDavAccount.load(context) == null, "Delete device credentials");
            account.save(context);
            runner.runOnMainSync(() -> activity.finish());
            activity = runner.startActivitySync(new Intent(context, WebDavActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitFor(() -> ((List<?>) field(activity, "entries")).size() == 4, "Browser reloads saved connection");
            runner.runOnMainSync(() -> {
                android.graphics.Bitmap image = runner.getUiAutomation().takeScreenshot();
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(new File(context.getExternalFilesDir(null), "webdav-browser.png"))) {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            // Exercise root plus child downloads, including a password-free redirected media URL.
            runner.runOnMainSync(() -> invoke(activity, "download", new Class<?>[]{WebDavClient.Entry.class}, new Object[]{null}));
            waitFor(() -> context.getSharedPreferences("dongting_player", Context.MODE_PRIVATE)
                    .getString("textBooks", "").contains("第二本.txt"), "Directory download returns imported book to main");
            result.putString("stream", "PASS: encrypted device account, DAV/Chinese directories, safe XML parsing, private file/directory downloads, reader import, auth errors, redirect boundaries, interrupted/cancelled downloads, authenticated playback/seek, redirect auth stripping, saved browser connection\n");
            runner.finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error)); runner.finish(0, result);
        } finally {
            if (activity != null) runner.runOnMainSync(() -> activity.finish());
            try { WebDavAccount.delete(context); } catch (Exception ignored) { }
        }
    }

    private static String response(String href, int status) {
        return "<d:response><d:href>" + href + "</d:href><d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 "
                + status + " Result</d:status></d:propstat></d:response>";
    }
    private static WebDavClient.Entry find(List<WebDavClient.Entry> entries, String name) {
        for (WebDavClient.Entry entry : entries) if (entry.name.equals(name)) return entry;
        throw new AssertionError("Missing fixture: " + name);
    }
    private static Object field(Object target, String name) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
    private static void invoke(Object target, String name, Class<?>[] types, Object... args) {
        try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(target, args); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
    private static void waitFor(BooleanSupplier predicate, String message) {
        long until = SystemClock.elapsedRealtime() + 20000;
        while (SystemClock.elapsedRealtime() < until) { if (predicate.getAsBoolean()) return; SystemClock.sleep(100); }
        throw new AssertionError("Timeout: " + message);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
