package com.nautrix.browser;

import java.net.IDN;
import java.net.URI;
import java.net.URLEncoder;
import java.util.Locale;

/** Converts an address-bar value to an HTTPS URL or a privacy-friendly search. */
public final class UrlResolver {
    private static final String SEARCH = "https://duckduckgo.com/?q=";

    private UrlResolver() {}

    public static String resolve(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "https://duckduckgo.com/";

        URI explicit = parse(value);
        if (explicit != null && explicit.getScheme() != null) {
            String scheme = explicit.getScheme().toLowerCase(Locale.ROOT);
            if ("https".equals(scheme)) {
                String safe = NavigationSecurityPolicy.safeHttpsUrl(value);
                if (safe != null) return safe;
            }
            if ("http".equals(scheme)) {
                String upgraded = NavigationSecurityPolicy.upgradeHttpToHttps(value);
                if (upgraded != null) return upgraded;
            }
            if ("about".equals(scheme) && "about:blank".equalsIgnoreCase(value)) {
                return "about:blank";
            }
            return search(value);
        }

        if (!value.contains(" ") && looksLikeHost(value)) {
            URI candidate = parse("https://" + value);
            if (candidate != null && candidate.getHost() != null) {
                return candidate.toASCIIString();
            }
        }
        return search(value);
    }

    private static boolean looksLikeHost(String value) {
        String host = value;
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) host = host.substring(0, colon);
        if ("localhost".equalsIgnoreCase(host)) return false;
        try {
            String ascii = IDN.toASCII(host);
            return ascii.contains(".") && !ascii.startsWith(".") && !ascii.endsWith(".");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static URI parse(String value) {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String search(String query) {
        try {
            return SEARCH + URLEncoder.encode(query, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
