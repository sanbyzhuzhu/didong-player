package com.dongting.player;

public final class UpdatePolicyTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    public static void main(String[] args) {
        check(!UpdatePolicy.showDot(21, 21, 0), "Installed release must not show a dot");
        check(!UpdatePolicy.showDot(21, 20, 0), "No downgrades");
        check(UpdatePolicy.showDot(21, 22, 0), "New release");
        check(!UpdatePolicy.showDot(21, 22, 22), "Ignored release");
        check(UpdatePolicy.showDot(21, 23, 22), "Newer release after ignore");
        check(!UpdatePolicy.showDot(21, 22, 23), "Do not notify about older ignored range");
        long now = 1000000000;
        check(UpdatePolicy.shouldCheck(now, 0, false), "First check");
        check(!UpdatePolicy.shouldCheck(now, now - 1000, true), "Daily successful cooldown");
        check(!UpdatePolicy.shouldCheck(now, now - 1000, false), "Failed check cooldown");
        check(UpdatePolicy.shouldCheck(now, now - UpdatePolicy.SUCCESS_INTERVAL_MS, true), "Daily retry");
        check(UpdatePolicy.shouldCheck(now, now - UpdatePolicy.FAILURE_INTERVAL_MS, false), "Failure retry");
        check(UpdatePolicy.shouldCheck(now, now + 100, true), "Clock rollback recovers");
        new UpdateInfo(22, "0.0.22", "notes", "https://github.com/sanbyzhuzhu/didong-player/releases/tag/v0.0.22",
                "https://gitee.com/sanbyzhu/didong-player");
        for (String bad : new String[]{"http://github.com/sanbyzhuzhu/didong-player/releases/tag/v0.0.22",
                "https://github.com.evil.example/sanbyzhuzhu/didong-player/releases/tag/v0.0.22",
                "https://github.com/other/repo/releases/tag/v0.0.22", "javascript:alert(1)",
                "https://github.com/sanbyzhuzhu/didong-player/releases/tag/v0.0.21"}) {
            boolean rejected = false;
            try { new UpdateInfo(22, "0.0.22", "", bad, "https://gitee.com/sanbyzhu/didong-player"); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Untrusted or mismatched release URL");
        }
        System.out.println("PASS: update version rules, ignore semantics, cooldowns, rollback and official URL validation");
    }
}
