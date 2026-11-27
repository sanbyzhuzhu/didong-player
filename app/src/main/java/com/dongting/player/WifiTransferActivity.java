package com.dongting.player;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class WifiTransferActivity extends AppCompatActivity {
    static final String EXTRA_URIS = "wifiTransferUris";
    private WifiTransferServer server;
    private TextView address, status;
    private Button start, copy, stop;
    private LinearLayout history;
    private String firstAddress = "";
    private boolean cleaning;
    private final List<File> received = Collections.synchronizedList(new ArrayList<>());

    static File root(Context context) { return new File(context.getNoBackupFilesDir(), "wifi-transfers"); }

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        UiKit.init(this); UiKit.applyWindowTheme(this);
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(UiKit.BG);
        UiKit.applySystemBarInsets(scroll, 0, 0);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        int padding = UiKit.dp(this, 16); content.setPadding(padding, padding, padding, padding); scroll.addView(content);
        content.addView(UiKit.label(this, "Wi-Fi 文件传输", 22, UiKit.TEXT));
        content.addView(UiKit.label(this, "电脑或另一部手机连接同一 Wi-Fi，用浏览器打开下方地址，即可批量上传影音和电子书。", 14, UiKit.SUBTLE));
        content.addView(UiKit.label(this, "手动开启；离开本页即关闭。请使用可信任的局域网，传输期间保持本页打开。", 13, UiKit.SUBTLE));
        address = UiKit.label(this, "尚未开启传输", 17, UiKit.ACCENT); address.setTextIsSelectable(true); content.addView(address);
        start = UiKit.button(this, "开启 Wi-Fi 传输", v -> startServer());
        stop = UiKit.button(this, "停止传输", v -> stopServer()); stop.setEnabled(false);
        copy = UiKit.button(this, "复制访问地址", v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null && !firstAddress.isEmpty()) {
                ClipData clip = ClipData.newPlainText("Wi-Fi 传输地址", firstAddress);
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    android.os.PersistableBundle extras = new android.os.PersistableBundle();
                    extras.putBoolean("android.content.extra.IS_SENSITIVE", true); clip.getDescription().setExtras(extras);
                }
                clipboard.setPrimaryClip(clip); status.setText("地址已复制，请在同一 Wi-Fi 下的浏览器中打开");
            }
        }); copy.setEnabled(false);
        content.addView(start); content.addView(UiKit.row(this, copy, stop));
        status = UiKit.label(this, "等待开启；收到文件后点击「完成并入库」。", 14, UiKit.TEXT);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); content.addView(status);
        content.addView(UiKit.button(this, "完成并入库", v -> finishWithReceived()));
        content.addView(UiKit.button(this, "导入全部已收文件", v -> {
            if (cleaning) return;
            stopServer();
            try { deliver(WifiTransferServer.files(root(this))); }
            catch (Exception error) { status.setText("读取接收记录失败，请重试"); }
        }));
        content.addView(UiKit.button(this, "清理全部已收文件", v -> {
            if (cleaning) return;
            new android.app.AlertDialog.Builder(this).setTitle("清理 Wi-Fi 已收文件？")
                    .setMessage("只删除通过本应用 Wi-Fi 接收的文件。已入库的相关条目将无法继续打开，需要重新传输。请先停止相关播放或朗读。")
                    .setNegativeButton("取消", null).setPositiveButton("清理", (dialog, which) -> {
                        stopServer(); cleaning = true; start.setEnabled(false); status.setText("正在清理…");
                        new Thread(() -> {
                            boolean success = true;
                            try {
                                WifiTransferServer.clear(root(this));
                            } catch (Exception error) { success = false; }
                            final boolean done = success;
                            runOnUiThread(() -> {
                                cleaning = false; received.removeIf(file -> !file.exists());
                                if (isDestroyed() || isFinishing()) return;
                                start.setEnabled(true); refreshHistory();
                                status.setText(done ? "已清理 Wi-Fi 接收文件" : "部分文件未能清理，请稍后重试");
                            });
                        }, "wifi-transfer-clean").start();
                    }).show();
        }));
        content.addView(UiKit.sectionTitle(this, "已接收文件（点选即可打开）"));
        history = new LinearLayout(this); history.setOrientation(LinearLayout.VERTICAL); content.addView(history);
        setContentView(scroll); refreshHistory();
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { finishWithReceived(); }
        });
    }

    private void startServer() {
        if (server != null || cleaning) return;
        try {
            Set<String> ips = addresses(this);
            if (ips.isEmpty()) { status.setText("未找到可用的 Wi-Fi IPv4 地址，请先连接 Wi-Fi 后重试"); return; }
            String html;
            try (InputStream input = getAssets().open("wifi-transfer.html"); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; for (int n; (n = input.read(buffer)) != -1;) bytes.write(buffer, 0, n);
                html = bytes.toString(StandardCharsets.UTF_8.name());
            }
            server = new WifiTransferServer(root(this), html,
                    name -> MediaUtils.isMedia(name) || BookTextParser.supportedName(name), BookTextParser::supportedName,
                    new WifiTransferServer.Listener() {
                        @Override public void received(File file) {
                            received.add(file);
                            runOnUiThread(() -> {
                                if (isFinishing() || isDestroyed()) return;
                                status.setText("已收到：" + file.getName() + "。点击「完成并入库」即可播放或朗读。"); refreshHistory();
                            });
                        }
                        @Override public void progress(String name, long done, long total) {
                            runOnUiThread(() -> {
                                if (server != null && !isFinishing() && !isDestroyed()) status.setText("正在接收：" + name + " · " + (done * 100 / total) + "%");
                            });
                        }
                    });
            StringBuilder urls = new StringBuilder();
            for (String ip : ips) {
                String url = "http://" + ip + ":" + server.port() + server.path();
                if (urls.length() == 0) firstAddress = url; else urls.append('\n');
                urls.append(url);
            }
            address.setText(urls); start.setEnabled(false); stop.setEnabled(true); copy.setEnabled(true);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            status.setText("传输已开启，请在同一 Wi-Fi 的浏览器输入完整地址。每次开启地址都会更换。\n若无法连接，请检查路由器是否开启了设备隔离。");
        } catch (Exception error) { stopServer(); status.setText("开启失败，请检查 Wi-Fi 连接后重试"); }
    }

    private void stopServer() {
        if (server != null) { server.close(); server = null; }
        firstAddress = "";
        if (start != null) { start.setEnabled(true); stop.setEnabled(false); copy.setEnabled(false); address.setText("传输已关闭，旧地址已失效"); status.setText("已完成的文件保留在手机中，未传完的文件不会入库。"); }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void refreshHistory() {
        history.removeAllViews();
        try {
            List<File> files = WifiTransferServer.files(root(this));
            if (files.isEmpty()) history.addView(UiKit.label(this, "还没有接收文件", 14, UiKit.SUBTLE));
            for (int i = 0; i < Math.min(100, files.size()); i++) {
                File file = files.get(i);
                Button entry = UiKit.button(this, file.getName() + " · " + android.text.format.Formatter.formatFileSize(this, file.length()),
                        v -> { stopServer(); deliver(Collections.singletonList(file)); });
                entry.setAllCaps(false); history.addView(entry);
            }
            if (files.size() > 100) history.addView(UiKit.label(this, "显示最近 100 个；可用「导入全部已收文件」批量入库。", 13, UiKit.SUBTLE));
        } catch (Exception error) { history.addView(UiKit.label(this, "暂时无法读取接收记录", 14, UiKit.SUBTLE)); }
    }

    private void finishWithReceived() {
        if (cleaning) { status.setText("正在清理，请稍候"); return; }
        stopServer();
        List<File> completed;
        synchronized (received) { completed = new ArrayList<>(received); }
        if (completed.isEmpty()) finish(); else deliver(completed);
    }

    private void deliver(List<File> files) {
        if (files.isEmpty()) { status.setText("还没有可导入的已收文件"); return; }
        ArrayList<String> uris = new ArrayList<>();
        for (File file : files) uris.add(Uri.fromFile(file).toString());
        android.os.Parcel parcel = android.os.Parcel.obtain();
        try {
            parcel.writeStringList(uris);
            if (parcel.dataSize() > 384 * 1024) { status.setText("文件路径总量过大，请点选文件分批导入"); return; }
        } finally { parcel.recycle(); }
        Intent result = new Intent().putStringArrayListExtra(EXTRA_URIS, uris);
        if (getIntent().getBooleanExtra("choose", false)) setResult(RESULT_OK, result);
        else startActivity(new Intent(this, MainActivity.class).putExtras(result)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    static Set<String> addresses(Context context) throws Exception {
        Set<String> ips = new LinkedHashSet<>();
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(CONNECTIVITY_SERVICE);
        if (manager != null) for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            if (capabilities == null || !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
            LinkProperties properties = manager.getLinkProperties(network);
            if (properties != null) for (LinkAddress link : properties.getLinkAddresses()) addAddress(ips, link.getAddress());
        }
        // Includes a phone hotspot's interface without exposing cellular or VPN addresses.
        java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces != null) for (NetworkInterface network : Collections.list(interfaces)) {
            String name = network.getName();
            if (!network.isUp() || !name.matches("(?:wlan|wifi|swlan|ap|eth)[A-Za-z0-9_.-]*")) continue;
            for (InetAddress ip : Collections.list(network.getInetAddresses())) addAddress(ips, ip);
        }
        return ips;
    }

    private static void addAddress(Set<String> ips, InetAddress ip) {
        if (ip instanceof Inet4Address && !ip.isLoopbackAddress() && (ip.isSiteLocalAddress() || ip.isLinkLocalAddress())) ips.add(ip.getHostAddress());
    }

    @Override protected void onStop() { stopServer(); super.onStop(); }
    @Override protected void onDestroy() { stopServer(); super.onDestroy(); }
}
