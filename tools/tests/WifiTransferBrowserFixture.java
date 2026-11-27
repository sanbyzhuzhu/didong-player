package com.dongting.player;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Local production-server fixture for interactive browser verification. */
public final class WifiTransferBrowserFixture {
    public static void main(String[] args) throws Exception {
        File root = new File("build/wifi-browser-files");
        String html = Files.readString(new File("app/src/main/assets/wifi-transfer.html").toPath(), StandardCharsets.UTF_8);
        try (WifiTransferServer server = new WifiTransferServer(root, html,
                name -> name.endsWith(".txt") || name.endsWith(".wav"), name -> name.endsWith(".txt"),
                new WifiTransferServer.Listener() {
                    public void received(File file) { System.out.println("Received " + file.getName()); }
                    public void progress(String name, long done, long total) { }
                })) {
            String url = "http://127.0.0.1:" + server.port() + server.path();
            Files.writeString(new File("build/wifi-browser-url.txt").toPath(), url, StandardCharsets.UTF_8);
            System.out.println(url);
            Thread.sleep(600000);
        }
    }
}
