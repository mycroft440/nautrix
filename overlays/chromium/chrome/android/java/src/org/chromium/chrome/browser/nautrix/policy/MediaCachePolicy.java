package org.chromium.chrome.browser.nautrix.policy;

/** Retention/eviction policy for the persistent smart media cache. */
public final class MediaCachePolicy {
    public static boolean isExpired(long nowMs, long lastAccessMs, boolean pinned, boolean playing) {
        if (pinned || playing) return false;
        if (lastAccessMs <= 0) return true;
        return nowMs - lastAccessMs >= NautrixConstants.MEDIA_CACHE_TTL_MS;
    }

    /** Lower values are evicted first. Complete and recently accessed media get a large bonus. */
    public static long retentionScore(long nowMs, long lastAccessMs, boolean complete,
            boolean pinned, boolean playing) {
        if (playing) return Long.MAX_VALUE;
        if (pinned) return Long.MAX_VALUE - 1;
        long age = Math.max(0L, nowMs - Math.max(0L, lastAccessMs));
        long freshness = Math.max(0L, NautrixConstants.MEDIA_CACHE_TTL_MS - age);
        return freshness + (complete ? NautrixConstants.MEDIA_CACHE_TTL_MS * 2L : 0L);
    }

    private MediaCachePolicy() {}
}
