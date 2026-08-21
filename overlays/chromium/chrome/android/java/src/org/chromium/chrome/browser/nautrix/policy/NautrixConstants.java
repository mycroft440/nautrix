package org.chromium.chrome.browser.nautrix.policy;

/** Cross-feature defaults kept dependency-free so they can be unit tested without Chromium. */
public final class NautrixConstants {
    public static final long HOUR_MS = 60L * 60L * 1000L;
    public static final long DAY_MS = 24L * HOUR_MS;
    public static final long NEVER_USED_TAB_TTL_MS = HOUR_MS;
    public static final long USED_TAB_TTL_MS = 5L * DAY_MS;
    public static final long MEDIA_CACHE_TTL_MS = 5L * DAY_MS;
    public static final long TAB_SWEEP_INTERVAL_MS = 15L * 60L * 1000L;
    public static final int DNS_SAMPLES = 5;
    public static final double DNS_SWITCH_HYSTERESIS = 0.10;
    public static final long DEFAULT_MEDIA_CACHE_BYTES = 5L * 1024L * 1024L * 1024L;

    private NautrixConstants() {}
}
