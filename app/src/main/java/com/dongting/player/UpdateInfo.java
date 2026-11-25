package com.dongting.player;

import java.net.URI;

/** Small, immutable update description. Only this project's official release links are allowed. */
final class UpdateInfo {
    final long versionCode;
    final String versionName;
    final String notes;
    final String releaseUrl;
    final String mirrorUrl;

    UpdateInfo(long versionCode, String versionName, String notes, String releaseUrl, String mirrorUrl) {
        if (versionCode <= 0 || versionCode > Integer.MAX_VALUE || versionName == null
                || versionName.length() > 40 || !versionName.matches("[0-9]+(?:\\.[0-9]+){1,3}"))
            throw new IllegalArgumentException("Invalid update version");
        if (!official(releaseUrl, "github.com", "/sanbyzhuzhu/didong-player/releases/tag/v" + versionName))
            throw new IllegalArgumentException("Invalid official release URL");
        if (!official(mirrorUrl, "gitee.com", "/sanbyzhu/didong-player"))
            throw new IllegalArgumentException("Invalid mirror URL");
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.notes = notes == null ? "" : notes.substring(0, Math.min(notes.length(), 4000));
        this.releaseUrl = releaseUrl;
        this.mirrorUrl = mirrorUrl;
    }

    private static boolean official(String value, String host, String path) {
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && host.equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null && uri.getPort() == -1 && path.equals(uri.getRawPath())
                    && uri.getQuery() == null && uri.getFragment() == null;
        } catch (Exception ignored) { return false; }
    }
}
