package com.dongting.player;

import android.content.Context;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

final class WebDavClient {
    static final class Entry {
        final HttpUrl url;
        final String name;
        final boolean directory;
        final long size;
        Entry(HttpUrl url, String name, boolean directory, long size) {
            this.url = url; this.name = name; this.directory = directory; this.size = size;
        }
        boolean supported() { return MediaUtils.isMedia(name) || BookTextParser.supportedName(name); }
    }

    private final WebDavAccount account;
    private final OkHttpClient client = new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();
    private volatile boolean cancelled;

    WebDavClient(WebDavAccount account) { this.account = account; }

    void cancel() { cancelled = true; client.dispatcher().cancelAll(); }

    private void checkCancelled() throws IOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("操作已取消");
    }

    private Response request(HttpUrl url, boolean listing) throws IOException {
        if (!account.contains(url)) throw new IOException("文件不在配置的连接目录内");
        for (int redirects = 0; redirects <= 5; redirects++) {
            checkCancelled();
            if (listing && !account.contains(url)) throw new IOException("服务器跳转到连接目录以外，已停止请求");
            if (!url.username().isEmpty() || !url.password().isEmpty()
                    || (account.root.isHttps() && !url.isHttps()))
                throw new IOException("服务器返回不安全的下载跳转，已停止请求");
            Request.Builder builder = new Request.Builder().url(url);
            if (account.contains(url) && (!account.username.isEmpty() || !account.password.isEmpty()))
                builder.header("Authorization", Credentials.basic(account.username, account.password, StandardCharsets.UTF_8));
            if (listing) builder.header("Depth", "1").method("PROPFIND", RequestBody.create(
                    MediaType.parse("application/xml; charset=utf-8"),
                    "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"));
            Response response = client.newCall(builder.build()).execute();
            int code = response.code();
            if (code >= 300 && code < 400 && response.header("Location") != null) {
                HttpUrl next = url.resolve(response.header("Location"));
                response.close();
                if (next == null) throw new IOException("无效的服务器跳转");
                url = next;
                continue;
            }
            if (listing ? code != 207 : code != 200) {
                response.close();
                if (code == 401 || code == 403) throw new IOException("账号密码错误或没有访问权限（HTTP " + code + "）");
                if (code == 404) throw new IOException("目录或文件不存在（HTTP 404）");
                throw new IOException("服务器返回 HTTP " + code + (listing ? "，请确认地址支持 WebDAV" : ""));
            }
            return response;
        }
        throw new IOException("服务器跳转次数过多");
    }

    List<Entry> list(HttpUrl directory) throws Exception {
        try (Response response = request(directory, true)) {
            if (response.body() == null) throw new IOException("服务器没有返回目录内容");
            try (InputStream input = response.body().byteStream()) {
                return parse(input, response.request().url(), account);
            }
        }
    }

    /** Namespace-aware DAV parsing; only successful properties and immediate children are trusted. */
    static List<Entry> parse(InputStream input, HttpUrl base, WebDavAccount account) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        parser.setInput(new java.io.FilterInputStream(input) {
            private long count;
            private void check(int n) throws IOException { count += Math.max(n, 0); if (count > 8 * 1024 * 1024) throw new IOException("目录响应过大，请选择较小的目录"); }
            @Override public int read() throws IOException { int n = super.read(); check(n < 0 ? 0 : 1); return n; }
            @Override public int read(byte[] b, int o, int l) throws IOException { int n = super.read(b, o, l); check(n); return n; }
        }, null);
        Map<String, Entry> entries = new LinkedHashMap<>();
        String href = "", status = "";
        boolean directory = false, propertyDirectory = false, successful = false;
        long size = -1, propertySize = -1;
        int responses = 0;
        String basePath = base.encodedPath();
        if (!basePath.endsWith("/")) basePath += "/";
        boolean multiStatus = false;
        for (int event = parser.nextToken(); event != XmlPullParser.END_DOCUMENT; event = parser.nextToken()) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("操作已取消");
            if (event == XmlPullParser.DOCDECL || event == XmlPullParser.ENTITY_REF)
                throw new IOException("不支持的目录 XML 内容");
            if (!"DAV:".equals(parser.getNamespace())) continue;
            String tag = parser.getName();
            if (event == XmlPullParser.START_TAG) {
                if (tag.equals("multistatus")) multiStatus = true;
                if (tag.equals("response")) {
                    if (++responses > 2001) throw new IOException("目录超过 2000 项，请在连接设置中填写较小的子目录地址");
                    href = ""; directory = false; size = -1; successful = false;
                } else if (tag.equals("propstat")) { status = ""; propertyDirectory = false; propertySize = -1; }
                else if (tag.equals("href")) href = parser.nextText();
                else if (tag.equals("collection")) propertyDirectory = true;
                else if (tag.equals("status")) status = parser.nextText();
                else if (tag.equals("getcontentlength")) {
                    try { propertySize = Long.parseLong(parser.nextText()); } catch (NumberFormatException ignored) { }
                }
            } else if (event == XmlPullParser.END_TAG) {
                if (tag.equals("propstat") && status.matches("HTTP/[^ ]+ 2[0-9]{2}(?: .*|$)")) {
                    successful = true; directory |= propertyDirectory;
                    if (propertySize >= 0) size = propertySize;
                } else if (tag.equals("response") && successful && !href.isEmpty()) {
                    HttpUrl url = base.resolve(href);
                    if (url == null || !account.contains(url)) continue;
                    String path = url.encodedPath();
                    if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
                    if (!path.startsWith(basePath)) continue;
                    String child = path.substring(basePath.length());
                    if (child.isEmpty() || child.contains("/")) continue;
                    // Encoded separators are not safe local filenames or directory boundaries.
                    String name = android.net.Uri.decode(child);
                    if (name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) continue;
                    if (directory && !url.encodedPath().endsWith("/")) url = url.newBuilder().addPathSegment("").build();
                    entries.put(url.toString(), new Entry(url, name, directory, size));
                }
            }
        }
        if (!multiStatus) throw new IOException("服务器返回的内容不是 WebDAV 目录");
        List<Entry> result = new ArrayList<>(entries.values());
        result.sort(Comparator.comparing((Entry e) -> !e.directory)
                .thenComparing(e -> e.name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    File download(Context context, Entry entry) throws Exception {
        checkCancelled();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                (account.username + "\n" + entry.url).getBytes(StandardCharsets.UTF_8));
        StringBuilder hash = new StringBuilder();
        for (byte value : digest) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        File directory = new File(downloadRoot(context), hash.toString());
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建本地下载目录");
        String name = entry.name.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", "_");
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.length() > 180)
            throw new IOException("文件名无效或过长");
        File target = new File(directory, name);
        File temporary = new File(directory, "download.part");
        long limit = BookTextParser.supportedName(entry.name) ? 64L * 1024 * 1024 : Long.MAX_VALUE;
        try (Response response = request(entry.url, false)) {
            if (response.body() == null) throw new IOException("文件内容为空");
            long length = response.body().contentLength();
            if (length > limit) throw new IOException("电子书大于 64 MB，请使用较小的文件");
            long available = directory.getUsableSpace() - 16L * 1024 * 1024;
            if (length > available) throw new IOException("手机存储空间不足");
            try (InputStream input = response.body().byteStream(); FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[32768];
                long total = 0;
                for (int n; (n = input.read(buffer)) != -1;) {
                    checkCancelled(); total += n;
                    if (total > limit || total > available) throw new IOException("文件过大或手机存储空间不足");
                    output.write(buffer, 0, n);
                }
                if (length >= 0 && total != length) throw new IOException("文件下载不完整，请重试");
                output.getFD().sync();
            }
            checkCancelled();
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return target;
        } finally { temporary.delete(); }
    }

    static File downloadRoot(Context context) { return new File(context.getNoBackupFilesDir(), "webdav-downloads"); }
}
