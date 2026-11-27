package com.dongting.player;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import okhttp3.HttpUrl;

/** Device-only credentials: excluded from OS backup and the application's settings export. */
final class WebDavAccount {
    private static final String KEY = "didong.webdav.v1";
    final HttpUrl root;
    final String username;
    final String password;

    WebDavAccount(String address, String username, String password) {
        HttpUrl url = HttpUrl.parse(address.trim());
        if (url == null || !(url.scheme().equals("https") || url.scheme().equals("http"))
                || !url.username().isEmpty() || !url.password().isEmpty()
                || url.query() != null || url.fragment() != null)
            throw new IllegalArgumentException("请输入 http:// 或 https:// 开头的目录地址，账号密码请填在下方");
        root = url.encodedPath().endsWith("/") ? url : url.newBuilder().addPathSegment("").build();
        if (username.contains(":")) throw new IllegalArgumentException("用户名不能包含冒号");
        this.username = username;
        this.password = password;
    }

    boolean contains(HttpUrl url) {
        return root.scheme().equals(url.scheme()) && root.host().equals(url.host())
                && root.port() == url.port() && url.username().isEmpty() && url.password().isEmpty()
                && url.query() == null && url.fragment() == null
                && (url.encodedPath().equals(root.encodedPath())
                    || url.encodedPath().startsWith(root.encodedPath()));
    }

    private static File file(Context context) {
        return new File(context.getNoBackupFilesDir(), "webdav-account.enc");
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY)) return (SecretKey) store.getKey(KEY, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    static synchronized WebDavAccount load(Context context) throws Exception {
        File file = file(context);
        if (!file.exists()) return null;
        byte[] bytes = Files.readAllBytes(file.toPath());
        if (bytes.length < 29) throw new java.io.IOException("连接配置损坏，请删除后重新添加");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, bytes, 0, 12));
        JSONObject json = new JSONObject(new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8));
        return new WebDavAccount(json.getString("url"), json.getString("username"), json.getString("password"));
    }

    synchronized void save(Context context) throws Exception {
        JSONObject json = new JSONObject().put("url", root.toString()).put("username", username).put("password", password);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(json.toString().getBytes(StandardCharsets.UTF_8));
        byte[] bytes = new byte[12 + encrypted.length];
        System.arraycopy(cipher.getIV(), 0, bytes, 0, 12);
        System.arraycopy(encrypted, 0, bytes, 12, encrypted.length);
        File temporary = new File(context.getNoBackupFilesDir(), "webdav-account.tmp");
        try {
            Files.write(temporary.toPath(), bytes);
            Files.move(temporary.toPath(), file(context).toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally { temporary.delete(); }
    }

    static synchronized void delete(Context context) throws Exception {
        Files.deleteIfExists(file(context).toPath());
    }
}
