package com.dongting.player;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.HttpUrl;

/** A private file picker. Credentials never appear in result intents, media URIs or backups. */
public final class WebDavActivity extends AppCompatActivity {
    static final String EXTRA_URIS = "webdavUris";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private WebDavAccount account;
    private WebDavClient client;
    private HttpUrl current;
    private LinearLayout root, files, toolbar;
    private TextView status, path;
    private Button cancel;
    private boolean busy;
    private volatile boolean operationCancelled;
    private int generation;
    private final List<WebDavClient.Entry> entries = new ArrayList<>();
    private boolean choosing;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        choosing = getIntent().getBooleanExtra("choose", false);
        UiKit.init(this);
        UiKit.applyWindowTheme(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(UiKit.BG);
        UiKit.applySystemBarInsets(scroll, 0, 0);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = UiKit.dp(this, 16);
        root.setPadding(padding, padding, padding, padding);
        scroll.addView(root);
        root.addView(UiKit.label(this, "WebDAV 云端文件", 22, UiKit.TEXT));
        root.addView(UiKit.label(this, "影音可在线播放；电子书下载后加入书架，支持离线朗读。", 13, UiKit.SUBTLE));
        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.VERTICAL);
        toolbar.addView(UiKit.row(this,
                button("连接设置", v -> configure()), button("删除连接", v -> deleteAccount())));
        toolbar.addView(UiKit.row(this,
                button("上级目录", v -> parent()), button("刷新", v -> browse(current))));
        toolbar.addView(UiKit.row(this,
                button("播放本目录", v -> streamDirectory()), button("下载整个目录", v -> downloadDirectory())));
        toolbar.addView(button("清理本地下载", v -> clearDownloads()));
        root.addView(toolbar);
        path = UiKit.label(this, "尚未连接", 13, UiKit.SUBTLE);
        root.addView(path);
        status = UiKit.label(this, "添加 WebDAV 地址以开始", 14, UiKit.TEXT);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status);
        cancel = button("取消操作", v -> cancelOperation());
        cancel.setVisibility(View.GONE);
        root.addView(cancel);
        files = new LinearLayout(this);
        files.setOrientation(LinearLayout.VERTICAL);
        root.addView(files);
        setContentView(scroll);
        try { account = WebDavAccount.load(this); }
        catch (Exception error) { status.setText("无法读取本机连接配置，请删除连接后重新设置"); }
        if (account != null) {
            current = account.root;
            if (state != null) {
                HttpUrl restored = HttpUrl.parse(state.getString("directory", account.root.toString()));
                if (restored != null && account.contains(restored)) current = restored;
            }
            browse(current);
        } else configure();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (current != null) state.putString("directory", current.toString());
        super.onSaveInstanceState(state);
    }

    private Button button(String title, View.OnClickListener click) {
        return UiKit.button(this, title, click);
    }

    private EditText input(String hint, String value, int type) {
        EditText text = new EditText(this);
        text.setHint(hint); text.setText(value); text.setSingleLine(true);
        text.setInputType(type); text.setTextColor(UiKit.TEXT); text.setHintTextColor(UiKit.SUBTLE);
        text.setBackgroundResource(R.drawable.dt_input);
        text.setSaveEnabled(false);
        return text;
    }

    private void configure() {
        if (busy) return;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = UiKit.dp(this, 16); form.setPadding(padding, 0, padding, 0);
        form.addView(UiKit.label(this, "账号密码仅加密保存在本机。建议使用 HTTPS；HTTP 会明文传输账号与文件。", 13, UiKit.SUBTLE));
        EditText url = input("目录地址（https://服务器/dav/）", account == null ? "" : account.root.toString(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText user = input("用户名（匿名访问可留空）", account == null ? "" : account.username, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        EditText password = input("密码 / 应用专用密码", account == null ? "" : account.password, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(url); form.addView(user); form.addView(password);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("WebDAV 连接设置").setView(form)
                .setPositiveButton("保存并连接", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(v -> {
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(click -> {
                try {
                    WebDavAccount next = new WebDavAccount(url.getText().toString(), user.getText().toString(), password.getText().toString());
                    next.save(this);
                    account = next; current = account.root;
                    dialog.dismiss(); browse(current);
                } catch (Exception error) {
                    Toast.makeText(this, error instanceof IllegalArgumentException ? error.getMessage() : "连接配置保存失败，请重试", Toast.LENGTH_LONG).show();
                }
            });
        });
        dialog.show();
    }

    private void deleteAccount() {
        if (busy) return;
        new AlertDialog.Builder(this).setTitle("删除本机 WebDAV 连接？")
                .setMessage("删除已保存的地址和账号密码；已下载的文件仍保留在本机。")
                .setPositiveButton("删除", (d, w) -> {
                    try {
                        WebDavAccount.delete(this); account = null; current = null;
                        entries.clear(); files.removeAllViews(); path.setText("尚未连接"); status.setText("连接已删除");
                    } catch (Exception error) { status.setText("删除失败，请重试"); }
                }).setNegativeButton("取消", null).show();
    }

    private void setEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setEnabled(group.getChildAt(i), enabled);
        }
    }

    private int begin(String message) {
        busy = true; generation++; operationCancelled = false;
        if (client != null) client.cancel();
        client = new WebDavClient(account);
        setEnabled(toolbar, false); setEnabled(files, false);
        status.setText(message); cancel.setVisibility(View.VISIBLE);
        return generation;
    }

    private boolean valid(int token) { return token == generation && !isFinishing() && !isDestroyed(); }

    private void idle() {
        busy = false; setEnabled(toolbar, true); setEnabled(files, true); cancel.setVisibility(View.GONE);
        cancel.setEnabled(true);
        for (int i = 0; i < entries.size() && i < files.getChildCount(); i++)
            files.getChildAt(i).setEnabled(entries.get(i).directory || entries.get(i).supported());
    }

    private void failure(int token, Exception error) {
        runOnUiThread(() -> {
            if (!valid(token)) return;
            idle();
            status.setText(error instanceof javax.net.ssl.SSLException ? "HTTPS 证书校验失败，请检查服务器证书"
                    : error instanceof java.net.SocketTimeoutException ? "连接超时，请检查网络后重试"
                    : error instanceof java.net.UnknownHostException ? "无法连接服务器，请检查地址和网络"
                    : error instanceof IOException ? "操作失败：" + error.getMessage() : "目录或文件无法读取，请确认服务器支持 WebDAV");
        });
    }

    private void browse(HttpUrl target) {
        if (busy || account == null || target == null) return;
        int token = begin("正在读取目录…");
        WebDavClient operation = client;
        executor.execute(() -> {
            try {
                List<WebDavClient.Entry> found = operation.list(target);
                runOnUiThread(() -> {
                    if (!valid(token)) return;
                    current = target; entries.clear(); entries.addAll(found);
                    files.removeAllViews();
                    for (WebDavClient.Entry entry : found) {
                        Button item = button((entry.directory ? "[目录] " : "") + entry.name
                                + (entry.directory || entry.size < 0 ? "" : "  ·  " + android.text.format.Formatter.formatFileSize(this, entry.size)),
                                v -> { if (entry.directory) browse(entry.url); else choose(entry); });
                        item.setAllCaps(false);
                        item.setEnabled(entry.directory || entry.supported());
                        files.addView(item);
                    }
                    idle();
                    // Reapply unsupported-file state after enabling the operation controls.
                    for (int i = 0; i < found.size(); i++) files.getChildAt(i).setEnabled(found.get(i).directory || found.get(i).supported());
                    path.setText(current.toString());
                    status.setText(found.isEmpty() ? "目录为空" : "共 " + found.size() + " 项；灰色文件暂不支持");
                });
            } catch (Exception error) { failure(token, error); }
        });
    }

    private void parent() {
        if (current == null || account == null || current.equals(account.root)) return;
        HttpUrl parent = current.resolve("../");
        if (parent != null && account.contains(parent)) browse(parent);
    }

    private void choose(WebDavClient.Entry entry) {
        if (busy || !entry.supported()) return;
        String[] choices = MediaUtils.isMedia(entry.name)
                ? new String[]{"在线播放", "下载到手机并加入列表"} : new String[]{"下载到手机并加入书架"};
        new AlertDialog.Builder(this).setTitle(entry.name).setItems(choices, (d, which) -> {
            if (MediaUtils.isMedia(entry.name) && which == 0) {
                ArrayList<String> uris = new ArrayList<>(); uris.add(entry.url.toString()); deliver(uris, "已加入云端文件");
            } else download(entry);
        }).setNegativeButton("取消", null).show();
    }

    private void streamDirectory() {
        if (busy || account == null) return;
        ArrayList<String> uris = new ArrayList<>();
        for (WebDavClient.Entry entry : entries) if (!entry.directory && MediaUtils.isMedia(entry.name)) uris.add(entry.url.toString());
        if (uris.isEmpty()) { status.setText("本目录没有可播放的影音文件"); return; }
        deliver(uris, "已加入本目录影音 " + uris.size() + " 个");
    }

    private void downloadDirectory() {
        if (busy || current == null) return;
        new AlertDialog.Builder(this).setTitle("下载整个目录？")
                .setMessage("递归下载本目录与子目录中的影音和电子书，消耗网络流量与手机存储。最多 2000 个文件，可随时取消；已完成的文件仍可导入。")
                .setPositiveButton("下载", (d, w) -> download(null)).setNegativeButton("取消", null).show();
    }

    private void download(@Nullable WebDavClient.Entry single) {
        int token = begin("正在准备下载…");
        WebDavClient operation = client;
        HttpUrl directory = current;
        executor.execute(() -> {
            ArrayList<String> completed = new ArrayList<>();
            try {
                List<WebDavClient.Entry> selected = new ArrayList<>();
                if (single != null) selected.add(single);
                else {
                    ArrayDeque<HttpUrl> pending = new ArrayDeque<>(); pending.add(directory);
                    Set<String> visited = new HashSet<>();
                    while (!pending.isEmpty()) {
                        HttpUrl folder = pending.removeFirst();
                        if (!visited.add(folder.toString())) continue;
                        if (visited.size() > 2000 || pending.size() > 2000) throw new IOException("目录数量超过 2000，请选择子目录下载");
                        for (WebDavClient.Entry entry : operation.list(folder)) {
                            if (entry.directory) pending.add(entry.url);
                            else if (entry.supported()) selected.add(entry);
                            if (selected.size() > 2000) throw new IOException("文件数量超过 2000，请选择子目录下载");
                        }
                    }
                }
                for (int i = 0; i < selected.size(); i++) {
                    WebDavClient.Entry entry = selected.get(i);
                    final String progress = "下载 " + (i + 1) + "/" + selected.size() + "：" + entry.name;
                    runOnUiThread(() -> { if (valid(token)) status.setText(progress); });
                    completed.add(Uri.fromFile(operation.download(this, entry)).toString());
                }
                runOnUiThread(() -> {
                    if (!valid(token)) return;
                    idle();
                    if (completed.isEmpty()) status.setText("目录中没有支持的影音或电子书文件");
                    else deliver(completed, "已下载并导入 " + completed.size() + " 个文件");
                });
            } catch (Exception error) {
                if (completed.isEmpty()) failure(token, error);
                else runOnUiThread(() -> {
                    if (!valid(token)) return;
                    idle();
                    new AlertDialog.Builder(this).setTitle("下载未全部完成")
                            .setMessage("已完成 " + completed.size() + " 个文件，其余文件请重试。")
                            .setPositiveButton("导入已完成文件", (d, w) -> deliver(completed, "已导入 " + completed.size() + " 个文件"))
                            .setNegativeButton("留在目录", null).show();
                });
            }
        });
    }

    private void cancelOperation() {
        operationCancelled = true;
        if (client != null) client.cancel();
        status.setText("正在取消，已完成的下载可继续导入…");
        cancel.setEnabled(false);
    }

    private void deliver(ArrayList<String> uris, String message) {
        android.os.Parcel parcel = android.os.Parcel.obtain();
        try {
            parcel.writeStringList(uris);
            if (parcel.dataSize() > 384 * 1024) {
                status.setText("文件路径总量过大，请进入较小的子目录分批导入；已下载文件仍保留在本机");
                return;
            }
        } finally { parcel.recycle(); }
        Intent result = new Intent().putStringArrayListExtra(EXTRA_URIS, uris);
        if (choosing) setResult(RESULT_OK, result);
        else startActivity(new Intent(this, MainActivity.class).putExtras(result)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    private void clearDownloads() {
        if (busy) return;
        new AlertDialog.Builder(this).setTitle("清理全部 WebDAV 本地下载？")
                .setMessage("清理后已导入的下载文件将无法离线打开，需要重新下载。请先停止相关播放或朗读。云端原文件和账号不会删除。")
                .setPositiveButton("清理", (d, w) -> {
                    int token = begin("正在清理…");
                    executor.execute(() -> {
                        try {
                            deleteFiles(WebDavClient.downloadRoot(this));
                            runOnUiThread(() -> { if (valid(token)) { idle(); status.setText("本地下载已清理，可在书架或列表中移除失效条目"); } });
                        } catch (Exception error) { failure(token, error); }
                    });
                }).setNegativeButton("取消", null).show();
    }

    private void deleteFiles(File file) throws IOException {
        if (operationCancelled || Thread.currentThread().isInterrupted()) throw new IOException("操作已取消");
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteFiles(child);
        if (file.exists() && !file.delete()) throw new IOException("部分文件无法清理，请稍后重试");
    }

    @Override protected void onDestroy() {
        generation++;
        if (client != null) client.cancel();
        executor.shutdownNow();
        super.onDestroy();
    }
}
