package org.chromium.chrome.browser.nautrix.policy;

import java.net.URI;
import java.util.Locale;

/** Protocol-first routing so downloads do not depend on fragile site-specific extractors. */
public final class DownloadPolicy {
    public enum Kind { HTTP, DIRECT_MEDIA, HLS, DASH, TORRENT, MAGNET, UNSUPPORTED }

    public static Kind classify(String raw) {
        if (raw == null || raw.isBlank()) return Kind.UNSUPPORTED;
        if (raw.regionMatches(true, 0, "magnet:", 0, 7)) return Kind.MAGNET;
        final URI uri;
        try { uri = URI.create(raw); } catch (IllegalArgumentException e) { return Kind.UNSUPPORTED; }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https") || scheme.equals("file"))) {
            return Kind.UNSUPPORTED;
        }
        if (path.endsWith(".torrent")) return Kind.TORRENT;
        if (path.endsWith(".m3u8")) return Kind.HLS;
        if (path.endsWith(".mpd")) return Kind.DASH;
        if (path.matches(".*\\.(mp4|webm|mov|m4v|mp3|m4a|aac|ogg|opus|wav)$")) {
            return Kind.DIRECT_MEDIA;
        }
        return Kind.HTTP;
    }

    private DownloadPolicy() {}
}
