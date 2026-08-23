package com.nautrix.browser;

import android.net.Uri;
import android.webkit.WebResourceRequest;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Small built-in denylist. It is intentionally conservative to avoid breaking pages. */
public final class TrackerBlocker {
    private static final Set<String> BLOCKED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "2mdn.net",
            "adnxs.com",
            "adsrvr.org",
            "amazon-adsystem.com",
            "analytics.google.com",
            "app-measurement.com",
            "criteo.com",
            "criteo.net",
            "doubleclick.net",
            "facebook.net",
            "google-analytics.com",
            "googlesyndication.com",
            "googletagmanager.com",
            "googletagservices.com",
            "hotjar.com",
            "scorecardresearch.com",
            "segment.io",
            "taboola.com"
    )));

    public boolean shouldBlock(WebResourceRequest request) {
        if (request == null || request.isForMainFrame()) return false;
        Uri uri = request.getUrl();
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || !("https".equals(scheme) || "http".equals(scheme))) return false;
        host = host.toLowerCase(java.util.Locale.ROOT);
        for (String blocked : BLOCKED) {
            if (host.equals(blocked) || host.endsWith("." + blocked)) return true;
        }
        return false;
    }
}
