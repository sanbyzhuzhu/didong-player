package com.dongting.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.Html;
import android.util.LruCache;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One extraction implementation shared by the library, player page and reading service. */
final class BookLoader {
    private static final LruCache<String, ReadableBook> CACHE = new LruCache<String, ReadableBook>(12 * 1024) {
        @Override protected int sizeOf(String key, ReadableBook value) {
            return Math.max(1, (value.text.length() * 2 + value.chapters.size() * 128) / 1024);
        }
    };

    private BookLoader() { }

    static synchronized void invalidate(Uri uri) {
        String prefix = uri + ":";
        for (String key : CACHE.snapshot().keySet()) if (key.startsWith(prefix)) CACHE.remove(key);
    }

    static synchronized ReadableBook load(Context context, Uri uri) throws IOException {
        String name = name(context, uri);
        String mime = context.getContentResolver().getType(uri);
        String stamp = "";
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            File file = new File(uri.getPath());
            stamp = file.length() + ":" + file.lastModified();
        } else {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int size = cursor.getColumnIndex(OpenableColumns.SIZE);
                    int modified = cursor.getColumnIndex("last_modified");
                    stamp = (size < 0 ? "" : cursor.getString(size)) + ":" + (modified < 0 ? "" : cursor.getString(modified));
                }
            } catch (RuntimeException ignored) { }
        }
        String key = uri + ":" + stamp;
        ReadableBook cached = CACHE.get(key);
        if (cached != null) return cached;
        SharedPreferences prefs = context.getSharedPreferences("dongting_player", Context.MODE_PRIVATE);
        boolean migration = "EPUB".equals(BookTextParser.format(name, mime))
                && !prefs.getBoolean("bookLayout2:" + uri, false)
                && (prefs.contains("ttsChunk:" + uri) || prefs.contains("textMarks:" + uri));
        ReadableBook book;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("无法打开文件，请重新授权");
            book = new BookTextParser(MediaUtils::decodeTextBytes,
                    value -> Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString())
                    .parse(input, name, mime, migration);
        } catch (RuntimeException error) {
            throw new IOException("文件内容无法解析：" + error.getClass().getSimpleName(), error);
        }
        if ("JSON".equals(book.format)) {
            StringBuilder text = new StringBuilder();
            try { appendJson(new JSONTokener(book.text).nextValue(), text, ""); } catch (Exception ignored) { }
            if (text.length() > 0) book = new ReadableBook(text.toString().trim(), Collections.emptyList(), book.warnings, "JSON", "");
        }
        if ("EPUB".equals(book.format)) {
            if (migration) book = migrate(prefs, uri.toString(), book);
            prefs.edit().putBoolean("bookLayout2:" + uri, true).apply();
        }
        CACHE.put(key, book);
        return book;
    }

    static String name(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0 && cursor.getString(index) != null) return cursor.getString(index);
            }
        } catch (RuntimeException ignored) { }
        return uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
    }

    private static ReadableBook migrate(SharedPreferences prefs, String uri, ReadableBook book) {
        List<String> warnings = new ArrayList<>(book.warnings);
        int oldChunk = prefs.getInt("ttsChunk:" + uri, 0);
        int oldOffset = prefs.getInt("ttsSentenceStart:" + uri, 0);
        String marks = prefs.getString("textMarks:" + uri, "[]");
        SharedPreferences.Editor editor = prefs.edit().putInt("legacyEpubChunk:" + uri, oldChunk)
                .putInt("legacyEpubOffset:" + uri, oldOffset).putString("legacyEpubMarks:" + uri, marks);
        TtsText.Position position = BookProgress.remap(book.legacyText, book.text, oldChunk, oldOffset);
        if (position == null) {
            position = new TtsText.Position(0, 0);
            warnings.add("正文顺序已修正，旧进度未能精确匹配，请从目录重新定位；原进度已备份");
        }
        editor.putInt("ttsChunk:" + uri, position.chunk).putInt("ttsSentenceChunk:" + uri, position.chunk)
                .putInt("ttsSentenceStart:" + uri, position.offset).putInt("ttsSentenceEnd:" + uri, position.offset)
                .putInt("ttsRangeStart:" + uri, position.offset).putInt("ttsRangeEnd:" + uri, position.offset)
                .putString("ttsVisualMode:" + uri, "sentence");
        JSONArray updated = new JSONArray();
        try {
            JSONArray old = new JSONArray(marks);
            for (int i = 0; i < old.length(); i++) {
                TtsText.Position mapped = BookProgress.remap(book.legacyText, book.text, old.getInt(i), 0);
                if (mapped != null) {
                    boolean duplicate = false;
                    for (int j = 0; j < updated.length(); j++) if (updated.getInt(j) == mapped.chunk) duplicate = true;
                    if (!duplicate) updated.put(mapped.chunk);
                }
            }
            editor.putString("textMarks:" + uri, updated.toString());
        } catch (Exception ignored) { }
        editor.apply();
        return new ReadableBook(book.text, book.chapters, warnings, book.format, "");
    }

    private static void appendJson(Object value, StringBuilder text, String label) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) { String key = keys.next(); appendJson(object.get(key), text, key); }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) appendJson(array.get(i), text, label);
        } else if (value != null && value != JSONObject.NULL && !String.valueOf(value).trim().isEmpty()) {
            if (!label.isEmpty()) text.append(label).append('：');
            text.append(value).append('\n');
        }
    }
}
