package com.dongting.player;

import android.content.Context;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class WebDavMedia {
    private WebDavMedia() { }

    static DefaultMediaSourceFactory sources(Context context) {
        Context app = context.getApplicationContext();
        OkHttpClient http = new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followSslRedirects(false)
                // Runs for every redirect: never forward a password to another origin or directory.
                .addNetworkInterceptor(chain -> {
                    Request.Builder request = chain.request().newBuilder().removeHeader("Authorization");
                    WebDavAccount account;
                    try { account = WebDavAccount.load(app); }
                    catch (Exception error) { account = null; }
                    if (account != null && account.contains(chain.request().url())
                            && (!account.username.isEmpty() || !account.password.isEmpty()))
                        request.header("Authorization", Credentials.basic(account.username, account.password, StandardCharsets.UTF_8));
                    return chain.proceed(request.build());
                }).build();
        return new DefaultMediaSourceFactory(new DefaultDataSource.Factory(app, new OkHttpDataSource.Factory(http)));
    }
}
