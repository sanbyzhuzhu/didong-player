package com.dongting.player;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Small session-scoped HTTP endpoint. One request per socket; uploads are raw, bounded streams. */
final class WifiTransferServer implements AutoCloseable {
    interface Listener {
        void received(File file);
        void progress(String name, long received, long total);
    }
    static final long MAX_BOOK = 64L * 1024 * 1024;
    static final long MAX_MEDIA = 32L * 1024 * 1024 * 1024;
    private final File root;
    private final Predicate<String> supported, book;
    private final Listener listener;
    private final String token = randomToken();
    private final String nonce = randomToken();
    private final byte[] page;
    private final ServerSocket server;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), task -> { Thread t = new Thread(task, "wifi-transfer"); t.setDaemon(true); return t; });
    private volatile boolean running = true;
    private boolean uploading;

    WifiTransferServer(File root, String html, Predicate<String> supported,
                       Predicate<String> book, Listener listener) throws IOException {
        this.root = root.getCanonicalFile(); this.supported = supported; this.book = book; this.listener = listener;
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("无法创建接收目录");
        page = html.replace("__NONCE__", nonce).getBytes(StandardCharsets.UTF_8);
        server = new ServerSocket();
        try { server.bind(new InetSocketAddress("0.0.0.0", 0), 8); }
        catch (IOException error) { server.close(); throw error; }
        Thread acceptor = new Thread(this::accept, "wifi-transfer-accept");
        acceptor.setDaemon(true); acceptor.start();
    }

    private static String randomToken() {
        byte[] bytes = new byte[16]; new SecureRandom().nextBytes(bytes);
        StringBuilder text = new StringBuilder();
        for (byte b : bytes) text.append(String.format(Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }

    int port() { return server.getLocalPort(); }
    String path() { return "/" + token + "/"; }

    private void accept() {
        while (running) {
            try {
                Socket socket = server.accept();
                socket.setSoTimeout(30000);
                if (!(socket.getInetAddress().isSiteLocalAddress() || socket.getInetAddress().isLinkLocalAddress()
                        || socket.getInetAddress().isLoopbackAddress())) { socket.close(); continue; }
                sockets.add(socket);
                try { workers.execute(() -> handle(socket)); }
                catch (java.util.concurrent.RejectedExecutionException rejected) { sockets.remove(socket); socket.close(); }
            } catch (IOException ignored) { if (!running) return; }
        }
    }

    private void handle(Socket socket) {
        try (Socket connection = socket;
             InputStream input = new BufferedInputStream(connection.getInputStream(), 32768);
             OutputStream output = new BufferedOutputStream(connection.getOutputStream(), 32768)) {
            try {
                Request request = readRequest(input);
                validateHost(request.headers.get("host"));
                String origin = request.headers.get("origin");
                if (origin != null && !origin.equals("http://" + request.headers.get("host")))
                    throw new Failure(403, "不允许跨站访问");
                if (!request.target.startsWith(path())) throw new Failure(403, "访问地址已失效，请从手机复制当前地址");
                if (request.headers.containsKey("transfer-encoding")) throw new Failure(400, "不支持分块请求，请使用页面上传");
                String route = request.target.substring(path().length());
                if (request.method.equals("GET") && route.isEmpty()) send(output, 200, "text/html; charset=utf-8", page, "");
                else if (request.method.equals("GET") && route.equals("list")) send(output, 200, "application/json; charset=utf-8", listing().getBytes(StandardCharsets.UTF_8), "");
                else if (request.method.equals("PUT") && route.startsWith("upload?name=")) upload(input, output, request, route.substring(12));
                else if (request.method.equals("GET") && route.startsWith("file/")) download(output, request, route.substring(5));
                else throw new Failure(404, "没有此功能或文件");
            } catch (Failure error) {
                send(output, error.code, "application/json; charset=utf-8", ("{\"error\":" + quote(error.getMessage()) + "}").getBytes(StandardCharsets.UTF_8), "");
            } catch (IOException error) {
                if (running) send(output, 500, "application/json; charset=utf-8", "{\"error\":\"传输中断或存储空间不足，请重试\"}".getBytes(StandardCharsets.UTF_8), "");
            }
            output.flush();
        } catch (IOException ignored) { }
        finally { sockets.remove(socket); }
    }

    private void validateHost(String host) throws Failure {
        if (host == null) throw new Failure(400, "缺少 Host");
        try {
            URI uri = URI.create("http://" + host);
            String name = uri.getHost();
            if (uri.getPort() != port() || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !uri.getRawPath().isEmpty()
                    || name == null || !name.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}"))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) { throw new Failure(403, "请使用手机显示的 IP 地址访问"); }
    }

    private static final class Request {
        final String method, target;
        final Map<String, String> headers = new HashMap<>();
        Request(String method, String target) { this.method = method; this.target = target; }
    }

    private static Request readRequest(InputStream input) throws IOException {
        String[] first = line(input, 4096).split(" ", -1);
        if (first.length != 3 || !first[1].startsWith("/") || first[1].contains("#")
                || !(first[2].equals("HTTP/1.1") || first[2].equals("HTTP/1.0"))) throw new Failure(400, "无效请求");
        Request request = new Request(first[0], first[1]);
        int total = 0, count = 0;
        for (String header; !(header = line(input, 8192)).isEmpty();) {
            total += header.length();
            if (total > 16384 || ++count > 64) throw new Failure(431, "请求头过大");
            int colon = header.indexOf(':');
            if (colon <= 0 || !header.substring(0, colon).matches("[A-Za-z0-9-]+")) throw new Failure(400, "无效请求头");
            String key = header.substring(0, colon).toLowerCase(Locale.ROOT);
            if (request.headers.put(key, header.substring(colon + 1).trim()) != null) throw new Failure(400, "重复请求头");
        }
        return request;
    }

    private static String line(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int value; (value = input.read()) != -1;) {
            if (value == '\r') {
                if (input.read() != '\n') throw new Failure(400, "无效请求行");
                return bytes.toString(StandardCharsets.ISO_8859_1.name());
            }
            if (value < 32 && value != '\t' || value == 127) throw new Failure(400, "无效请求字符");
            if (bytes.size() >= limit) throw new Failure(431, "请求行过长");
            bytes.write(value);
        }
        throw new IOException("请求已断开");
    }

    private void upload(InputStream input, OutputStream output, Request request, String encodedName) throws IOException {
        String name;
        try { name = URLDecoder.decode(encodedName, "UTF-8"); }
        catch (IllegalArgumentException error) { throw new Failure(400, "文件名编码无效"); }
        if (encodedName.contains("&") || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\") || name.matches("(?s).*[\\p{Cntrl}].*")
                || name.getBytes(StandardCharsets.UTF_8).length > 200) throw new Failure(400, "文件名无效或过长");
        if (!supported.test(name)) throw new Failure(415, "仅支持影音与可朗读电子书文件");
        String lengthHeader = request.headers.get("content-length");
        if (lengthHeader == null) throw new Failure(411, "需要文件长度，请使用页面上传");
        long length;
        try { if (!lengthHeader.matches("[0-9]+")) throw new NumberFormatException(); length = Long.parseLong(lengthHeader); }
        catch (NumberFormatException error) { throw new Failure(400, "文件长度无效"); }
        if (length <= 0) throw new Failure(400, "空文件无法播放或朗读");
        if (length > (book.test(name) ? MAX_BOOK : MAX_MEDIA)) throw new Failure(413, "电子书最大 64 MB，影音最大 32 GB");
        if (length > root.getUsableSpace() - 16L * 1024 * 1024) throw new Failure(507, "手机存储空间不足");
        synchronized (this) {
            if (!running) throw new IOException("传输已关闭");
            if (uploading) throw new Failure(409, "已有文件正在上传，请稍后重试");
            if (files(root).size() >= 2000) throw new Failure(507, "已收文件超过 2000 个，请先清理");
            uploading = true;
        }
        File folder = new File(root, UUID.randomUUID().toString().replace("-", ""));
        File temporary = new File(folder, "upload.part");
        boolean complete = false;
        try {
            String expect = request.headers.get("expect");
            if (expect != null) {
                if (!expect.equalsIgnoreCase("100-continue")) throw new Failure(417, "不支持此上传方式");
                output.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); output.flush();
            }
            if (!folder.mkdir()) throw new IOException("无法创建接收目录");
            long total = 0, lastProgress = 0;
            try (FileOutputStream file = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[65536];
                while (total < length) {
                    if (!running) throw new IOException("传输已关闭");
                    int n = input.read(buffer, 0, (int) Math.min(buffer.length, length - total));
                    if (n < 0) throw new IOException("文件未传完");
                    file.write(buffer, 0, n); total += n;
                    long now = System.nanoTime();
                    if (now - lastProgress > 250000000L) { listener.progress(name, total, length); lastProgress = now; }
                }
                file.getFD().sync();
            }
            File target = new File(folder, name);
            synchronized (this) {
                if (!running) throw new IOException("传输已关闭");
                Files.move(temporary.toPath(), target.toPath()); complete = true;
                listener.received(target);
            }
            send(output, 201, "application/json; charset=utf-8",
                    ("{\"name\":" + quote(name) + ",\"size\":" + length + "}").getBytes(StandardCharsets.UTF_8), "");
        } finally {
            temporary.delete(); if (!complete) folder.delete();
            synchronized (this) { uploading = false; }
        }
    }

    static List<File> files(File root) throws IOException {
        root = root.getCanonicalFile();
        List<File> result = new ArrayList<>();
        File[] folders = root.listFiles();
        if (folders != null) for (File folder : folders) {
            if (!folder.getName().matches("[0-9a-f]{32}") || !folder.isDirectory()
                    || !folder.getCanonicalPath().equals(new File(root, folder.getName()).getAbsolutePath())) continue;
            File[] children = folder.listFiles();
            if (children != null) for (File file : children) if (file.isFile() && !file.getName().equals("upload.part")
                    && file.getCanonicalPath().equals(new File(folder, file.getName()).getAbsolutePath())) result.add(file);
        }
        result.sort(Comparator.comparingLong(File::lastModified).reversed().thenComparing(File::getName));
        return result;
    }

    private String listing() throws IOException {
        StringBuilder json = new StringBuilder("[");
        for (File file : files(root)) {
            if (json.length() > 1) json.append(',');
            json.append("{\"id\":").append(quote(file.getParentFile().getName()))
                    .append(",\"name\":").append(quote(file.getName())).append(",\"size\":").append(file.length()).append('}');
        }
        return json.append(']').toString();
    }

    /** Called only after stopping the server. Includes incomplete files left by process death. */
    static void clear(File root) throws IOException {
        root = root.getCanonicalFile();
        File[] folders = root.listFiles();
        if (folders == null) return;
        boolean failed = false;
        for (File folder : folders) {
            if (!folder.getName().matches("[0-9a-f]{32}") || !folder.isDirectory()
                    || !folder.getCanonicalPath().equals(new File(root, folder.getName()).getAbsolutePath())) continue;
            File[] children = folder.listFiles();
            if (children != null) for (File child : children) {
                if (!child.isFile() || !child.getCanonicalPath().equals(new File(folder, child.getName()).getAbsolutePath())
                        || !child.delete()) failed = true;
            }
            if (folder.exists() && !folder.delete()) failed = true;
        }
        if (failed) throw new IOException("部分已收文件无法清理");
    }

    private void download(OutputStream output, Request request, String id) throws IOException {
        if (!id.matches("[0-9a-f]{32}")) throw new Failure(404, "文件不存在");
        File target = null;
        for (File file : files(root)) if (file.getParentFile().getName().equals(id)) { target = file; break; }
        if (target == null) throw new Failure(404, "文件不存在");
        long length = target.length(), first = 0, last = length - 1;
        String range = request.headers.get("range");
        if (range != null) {
            try {
                if (!range.matches("bytes=[0-9]+-[0-9]*")) throw new NumberFormatException();
                String[] pair = range.substring(6).split("-", -1);
                first = Long.parseLong(pair[0]);
                if (!pair[1].isEmpty()) last = Math.min(last, Long.parseLong(pair[1]));
                if (first > last || first >= length) throw new NumberFormatException();
            } catch (NumberFormatException error) { throw new Failure(416, "请求范围无效"); }
        }
        String extra = "Accept-Ranges: bytes\r\nContent-Disposition: attachment; filename=\"download\"; filename*=UTF-8''"
                + URLEncoder.encode(target.getName(), "UTF-8").replace("+", "%20") + "\r\n";
        if (range != null) extra += "Content-Range: bytes " + first + "-" + last + "/" + length + "\r\n";
        headers(output, range == null ? 200 : 206, "application/octet-stream", last - first + 1, extra);
        try (FileInputStream file = new FileInputStream(target)) {
            file.getChannel().position(first);
            byte[] buffer = new byte[65536];
            for (long remaining = last - first + 1; remaining > 0;) {
                if (!running) throw new IOException("传输已关闭");
                int n = file.read(buffer, 0, (int) Math.min(remaining, buffer.length));
                if (n < 0) throw new IOException("文件读取中断");
                output.write(buffer, 0, n); remaining -= n;
            }
        }
    }

    private void headers(OutputStream output, int code, String mime, long length, String extra) throws IOException {
        String headers = "HTTP/1.1 " + code + " " + (code < 400 ? "OK" : "Error") + "\r\nContent-Type: " + mime
                + "\r\nContent-Length: " + length + "\r\nConnection: close\r\nCache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\n"
                + "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-" + nonce
                + "'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'\r\n" + extra + "\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
    }

    private void send(OutputStream output, int code, String mime, byte[] bytes, String extra) throws IOException {
        headers(output, code, mime, bytes.length, extra); output.write(bytes);
    }

    static String quote(String text) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '"') result.append('\\').append(c);
            else if (c < 32) result.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else result.append(c);
        }
        return result.append('"').toString();
    }

    private static final class Failure extends IOException {
        final int code;
        Failure(int code, String message) { super(message); this.code = code; }
    }

    @Override public synchronized void close() {
        running = false;
        try { server.close(); } catch (IOException ignored) { }
        for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
        sockets.clear(); workers.shutdownNow();
    }
}
