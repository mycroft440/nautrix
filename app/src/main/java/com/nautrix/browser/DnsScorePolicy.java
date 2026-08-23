package com.nautrix.browser;

/** Pure scoring policy: fast, consistent resolvers beat erratic or failing ones. */
public final class DnsScorePolicy {
    private DnsScorePolicy() {
    }

    public static boolean isStable(int successes) {
        return successes >= 2;
    }

    public static long score(int averageMs, long minimumMs, long maximumMs,
                             int successes, int timeoutMs) {
        if (successes <= 0 || averageMs == Integer.MAX_VALUE) return Long.MAX_VALUE;
        long jitter = successes < 2 ? timeoutMs : Math.max(0L, maximumMs - minimumMs);
        return averageMs + jitter + ((3L - Math.min(3, successes)) * timeoutMs);
    }
}
