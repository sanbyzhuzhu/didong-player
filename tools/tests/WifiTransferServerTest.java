package com.dongting.player;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class WifiTransferServerTest {
    private static final List<File> received = Collections.synchronizedList(new ArrayList<>());
    private static final WifiTransferServer.Listener listener = new WifiTransferServer.Listener() {
        public void received(File file) { received.add(file); }
        public void progress(String name, long done, long total) { }
    };
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static WifiTransferServer server(File root) throws Exception {
        return new WifiTransferServer(root, "<script nonce='__NONCE__'>test</script>",
                name -> name.endsWith(".txt") || name.endsWith(".wav"), name -> name.endsWith(".txt"), listener);
    }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("didong-wifi-test-").toFile();
        WifiTransferServer server = server(root);
        String base = "http://127.0.0.1:" + server.port() + server.path();
        byte[] text = "Wi-Fi 中文电子书。第二句话。".getBytes(StandardCharsets.UTF_8);
        try {
            check(get(base).contains("nonce='") && !get(base).contains("__NONCE__"), "Serve page with CSP nonce");
            check(get(base + "list").equals("[]"), "Empty file list");
            check(put(base + "upload?name=" + URLEncoder.encode("中文 书籍.txt", "UTF-8"), text) == 201, "Chinese upload");
            check(put(base + "upload?name=" + URLEncoder.encode("中文 书籍.txt", "UTF-8"), text) == 201, "Duplicate filename upload");
            check(received.size() == 2 && !received.get(0).equals(received.get(1)), "Duplicate names do not overwrite");
            String id = received.get(0).getParentFile().getName();
            check(get(base + "file/" + id).equals(new String(text, StandardCharsets.UTF_8)), "Download round trip");
            HttpURLConnection range = (HttpURLConnection) new URL(base + "file/" + id).openConnection();
            range.setRequestProperty("Range", "bytes=1-4");
            check(range.getResponseCode() == 206 && range.getInputStream().readAllBytes().length == 4, "HTTP Range download"); range.disconnect();
            check(get(base + "list").contains("中文 书籍.txt"), "List completed files");
            check(status(server, "GET /wrong/list HTTP/1.1\r\n", "", "") == 403, "Session token required");
            check(status(server, "GET " + server.path() + "list HTTP/1.1\r\n", "Host: evil.example:" + server.port() + "\r\n", "") == 403, "DNS rebinding host rejected");
            check(status(server, "GET " + server.path() + "list HTTP/1.1\r\n", "Origin: http://evil.example\r\n", "") == 403, "Foreign origin rejected");
            check(put(base + "upload?name=..%2Fescape.txt", text) == 400, "Path traversal rejected");
            check(put(base + "upload?name=bad%5Cname.txt", text) == 400, "Backslash rejected");
            check(put(base + "upload?name=program.apk", text) == 415, "Unsupported file rejected");
            check(put(base + "upload?name=empty.txt", new byte[0]) == 400, "Empty file rejected");
            String upload = "PUT " + server.path() + "upload?name=sample.txt HTTP/1.1\r\n";
            check(status(server, upload, "", "") == 411, "Upload length required");
            check(status(server, upload, "Content-Length: 1\r\nContent-Length: 2\r\n", "x") == 400, "Duplicate length rejected");
            check(status(server, upload, "Content-Length: 1\r\nTransfer-Encoding: chunked\r\n", "x") == 400, "Ambiguous length rejected");
            check(status(server, upload, "Content-Length: -1\r\n", "") == 400, "Negative length rejected");
            check(status(server, upload, "Content-Length: 999999999\r\n", "") == 413, "Oversize book rejected before reading body");
            check(status(server, upload, "Content-Length: 1\r\nExpect: unsupported\r\n", "x") == 417, "Unknown expectation rejected");
            try (Socket socket = new Socket("127.0.0.1", server.port())) {
                socket.getOutputStream().write((upload + "Host: 127.0.0.1:" + server.port()
                        + "\r\nContent-Length: 100\r\n\r\nxx").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            }
            Thread.sleep(250);
            check(received.size() == 2 && WifiTransferServer.files(root).size() == 2, "Interrupted upload not committed");
            try (Socket socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write((upload + "Host: 127.0.0.1:" + server.port()
                        + "\r\nContent-Length: 100\r\n\r\nxx").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush(); Thread.sleep(100); server.close();
                check(socket.getInputStream().read() == -1, "Stop closes active upload");
            }
            Thread.sleep(100);
            check(WifiTransferServer.files(root).size() == 2, "Stopped partial file not listed");
            String oldPath = server.path();
            try (WifiTransferServer restarted = server(root)) {
                check(!oldPath.equals(restarted.path()), "New session rotates access key");
                check(status(restarted, "GET " + oldPath + "list HTTP/1.1\r\n", "", "") == 403, "Old key invalid after restart");
                check(get("http://127.0.0.1:" + restarted.port() + restarted.path() + "list").contains(id), "Files persist across restart");
            }
            File orphan = new File(root, "01234567890123456789012345678901"); orphan.mkdir();
            Files.write(new File(orphan, "upload.part").toPath(), new byte[]{1});
            WifiTransferServer.clear(root);
            check(WifiTransferServer.files(root).isEmpty() && !orphan.exists(), "Clear removes completed and orphan partial files");
            System.out.println("PASS: Wi-Fi upload/download, Chinese and duplicate names, Range, session/Host/Origin boundaries, length/format limits, interrupted uploads and stop/restart");
        } finally { server.close(); delete(root); }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(3000); connection.setReadTimeout(3000);
        try { check(connection.getResponseCode() == 200, "GET " + url); return new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8); }
        finally { connection.disconnect(); }
    }
    private static int put(String url, byte[] bytes) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(3000); connection.setReadTimeout(3000); connection.setRequestMethod("PUT");
        connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length);
        try { try (OutputStream output = connection.getOutputStream()) { output.write(bytes); } return connection.getResponseCode(); }
        finally { connection.disconnect(); }
    }
    private static int status(WifiTransferServer server, String first, String headers, String body) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(3000);
            if (!headers.toLowerCase().contains("host:")) headers += "Host: 127.0.0.1:" + server.port() + "\r\n";
            socket.getOutputStream().write((first + headers + "\r\n" + body).getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            for (int c; (c = socket.getInputStream().read()) != '\n' && c >= 0;) line.write(c);
            return Integer.parseInt(line.toString(StandardCharsets.US_ASCII.name()).split(" ")[1]);
        }
    }
    private static void delete(File file) {
        File[] children = file.listFiles(); if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
