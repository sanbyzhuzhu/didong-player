package com.dongting.player;

/** No floating-point version comparisons: Android versionCode is authoritative. */
final class UpdatePolicy {
    static final long SUCCESS_INTERVAL_MS = 24L * 60 * 60 * 1000;
    static final long FAILURE_INTERVAL_MS = 2L * 60 * 60 * 1000;

    private UpdatePolicy() { }

    static boolean showDot(long installed, long available, long ignored) {
        return available > installed && available > ignored;
    }

    static boolean shouldCheck(long now, long lastAttempt, boolean lastSucceeded) {
        if (lastAttempt <= 0 || now < lastAttempt) return true;
        return now - lastAttempt >= (lastSucceeded ? SUCCESS_INTERVAL_MS : FAILURE_INTERVAL_MS);
    }
}
