package org.chromium.chrome.browser.nautrix.policy;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Dependency-free magnet validation used before the native torrent service is started. */
public final class TorrentPolicy {
    public static final class Magnet {
        /** Full xt value (for example urn:btih:... or urn:btmh:1220...). */
        public final String exactTopic;
        public final String infoHash;
        public final boolean v2;
        public final String displayName;
        public final List<String> trackers;

        Magnet(
                String exactTopic,
                String infoHash,
                boolean v2,
                String displayName,
                List<String> trackers) {
            this.exactTopic = exactTopic;
            this.infoHash = infoHash;
            this.v2 = v2;
            this.displayName = displayName;
            this.trackers = Collections.unmodifiableList(trackers);
        }
    }

    /**
     * Accepts BitTorrent v1 BTIH magnets and BitTorrent v2 SHA-256 multihash magnets.
     *
     * <p>For v2, BEP 52 commonly represents the exact topic as
     * {@code urn:btmh:1220<64 hex chars>}: 0x12 = SHA-256, 0x20 = 32-byte digest.
     */
    public static Magnet parseMagnet(String raw) {
        if (raw == null || !raw.regionMatches(true, 0, "magnet:?", 0, 8)) return null;
        String query = raw.substring(raw.indexOf('?') + 1);
        String topic = null;
        String hash = null;
        boolean v2 = false;
        String name = "";
        List<String> trackers = new ArrayList<>();

        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            String key = decode(eq < 0 ? part : part.substring(0, eq));
            String value = decode(eq < 0 ? "" : part.substring(eq + 1));
            if (key.equalsIgnoreCase("xt")) {
                if (value.regionMatches(true, 0, "urn:btih:", 0, 9)) {
                    String candidate = value.substring(9);
                    if (candidate.matches("(?i)[0-9a-f]{40}")
                            || candidate.matches("(?i)[a-z2-7]{32}")) {
                        topic = value;
                        hash = candidate;
                        v2 = false;
                    }
                } else if (value.regionMatches(true, 0, "urn:btmh:", 0, 9)) {
                    String candidate = value.substring(9);
                    if (candidate.matches("(?i)1220[0-9a-f]{64}")) {
                        topic = value;
                        hash = candidate.substring(4);
                        v2 = true;
                    }
                }
            } else if (key.equalsIgnoreCase("dn")) {
                name = value;
            } else if (key.equalsIgnoreCase("tr") && !value.isBlank()) {
                trackers.add(value);
            }
        }
        return topic == null ? null : new Magnet(topic, hash, v2, name, trackers);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private TorrentPolicy() {}
}
