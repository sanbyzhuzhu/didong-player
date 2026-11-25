package com.dongting.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Best-effort update metadata only: never downloads APKs, installs, notifies or opens UI. */
final class AppUpdates {
    static final String PREFS = "dongting_updates";
    static final String CACHE = "cached_manifest";
    static final String IGNORED = "ignored_version_code";
    static final String ATTEMPT = "last_attempt";
    static final String SUCCEEDED = "last_succeeded";
    static final String FINISHED = "last_finished";
    static final String REVISION = "revision";
    static final String SOURCE = "source";
    static final String GITHUB = "https://raw.githubusercontent.com/sanbyzhuzhu/didong-player/main/updates/android.json";
    static final String GITEE = "https://gitee.com/api/v5/repos/sanbyzhu/didong-player/contents/updates/android.json";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static volatile boolean checking;

    interface Fetcher { String fetch(String url) throws Exception; }
    private AppUpdates() { }

    static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static long installedCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception ignored) { return Long.MAX_VALUE; }
    }

    static String installedName(Context context) {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (Exception ignored) { return "未知"; }
    }

    static boolean isChecking() { return checking; }

    static UpdateInfo parse(String raw) throws Exception {
        if (raw == null || raw.length() > 65536) throw new IOException("Update metadata too large");
        JSONObject json = new JSONObject(raw);
        if (json.optInt("schemaVersion", 0) != 1) throw new IOException("Unsupported update metadata schema");
        Object code = json.get("versionCode");
        if (!(code instanceof Integer) && !(code instanceof Long)) throw new IOException("Invalid versionCode");
        return new UpdateInfo(((Number) code).longValue(), json.getString("versionName"),
                json.optString("releaseNotes", ""), json.getString("releaseUrl"), json.getString("mirrorUrl"));
    }

    static UpdateInfo cached(Context context) {
        try { return parse(preferences(context).getString(CACHE, "")); }
        catch (Exception ignored) { return null; }
    }

    static boolean showDot(Context context) {
        UpdateInfo info = cached(context);
        return info != null && UpdatePolicy.showDot(installedCode(context), info.versionCode,
                preferences(context).getLong(IGNORED, 0));
    }

    static void ignore(Context context, UpdateInfo info) {
        preferences(context).edit().putLong(IGNORED, info.versionCode).apply();
    }

    static boolean checkInBackground(Context context, boolean force) {
        try { return checkInBackground(context, force, AppUpdates::fetch); }
        catch (RuntimeException ignored) { checking = false; return false; }
    }

    static synchronized boolean checkInBackground(Context context, boolean force, Fetcher fetcher) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = preferences(app);
        if (checking || (!force && !UpdatePolicy.shouldCheck(System.currentTimeMillis(),
                prefs.getLong(ATTEMPT, 0), prefs.getBoolean(SUCCEEDED, false)))) return false;
        checking = true;
        prefs.edit().putLong(ATTEMPT, System.currentTimeMillis()).apply();
        EXECUTOR.execute(() -> {
            try {
                String accepted = null;
                String source = "";
                for (String endpoint : new String[]{GITHUB, GITEE}) {
                    try {
                        String raw = fetcher.fetch(endpoint);
                        if (endpoint.equals(GITEE)) {
                            JSONObject file = new JSONObject(raw);
                            if (!"base64".equals(file.getString("encoding")) || file.optLong("size", 0) > 65536)
                                throw new IOException("Invalid mirror metadata");
                            raw = new String(android.util.Base64.decode(file.getString("content"), android.util.Base64.DEFAULT), StandardCharsets.UTF_8);
                        }
                        parse(raw);
                        accepted = raw;
                        source = endpoint.equals(GITHUB) ? "GitHub" : "Gitee";
                        break;
                    } catch (Exception ignored) { /* Silent fallback; never touch playback or reader state. */ }
                }
                synchronized (AppUpdates.class) {
                    checking = false;
                    SharedPreferences.Editor editor = prefs.edit()
                            .putBoolean(SUCCEEDED, accepted != null).putLong(FINISHED, System.currentTimeMillis())
                            .putInt(REVISION, prefs.getInt(REVISION, 0) + 1);
                    if (accepted != null) editor.putString(CACHE, accepted).putString(SOURCE, source);
                    editor.apply();
                }
            } catch (RuntimeException ignored) { checking = false; }
        });
        return true;
    }

    private static String fetch(String endpoint) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(4000);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setRequestProperty("User-Agent", "DidongPlayer-UpdateCheck");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Update endpoint unavailable");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    if (out.size() + length > 65536) throw new IOException("Update metadata too large");
                    out.write(buffer, 0, length);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally { connection.disconnect(); }
    }
}
