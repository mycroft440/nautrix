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
                String normalized = normalizeInternationalHost(value, "https");
                if (normalized != null) return normalized;
            }
            if ("http".equals(scheme)) {
                String upgraded = NavigationSecurityPolicy.upgradeHttpToHttps(value);
                if (upgraded != null) return upgraded;
                String normalized = normalizeInternationalHost(value, "https");
                if (normalized != null) return normalized;
            }
            if ("about".equals(scheme) && "about:blank".equalsIgnoreCase(value)) {
                return "about:blank";
            }
            return search(value);
        }

        if (!value.contains(" ")) {
            String normalized = normalizeBareHost(value);
            if (normalized != null) return normalized;
        }
        return search(value);
    }

    private static String normalizeBareHost(String value) {
        int boundary = authorityBoundary(value, 0);
        String authority = value.substring(0, boundary);
        String suffix = value.substring(boundary);
        String asciiAuthority = normalizeAuthority(authority);
        if (asciiAuthority == null) return null;
        return validateHttps("https://" + asciiAuthority + suffix);
    }

    private static String normalizeInternationalHost(String value, String targetScheme) {
        int separator = value.indexOf("://");
        if (separator <= 0) return null;
        int authorityStart = separator + 3;
        int boundary = authorityBoundary(value, authorityStart);
        String authority = value.substring(authorityStart, boundary);
        String suffix = value.substring(boundary);
        String asciiAuthority = normalizeAuthority(authority);
        if (asciiAuthority == null) return null;
        return validateHttps(targetScheme + "://" + asciiAuthority + suffix);
    }

    private static String normalizeAuthority(String authority) {
        if (authority.isEmpty() || authority.indexOf('@') >= 0) return null;
        if (authority.startsWith("[")) {
            // IPv6 literals are already ASCII and should be handled by java.net.URI as-is.
            return authority;
        }

        String host = authority;
        String port = "";
        int colon = authority.lastIndexOf(':');
        if (colon > 0 && authority.indexOf(':') == colon) {
            String candidatePort = authority.substring(colon + 1);
            if (candidatePort.isEmpty() || !candidatePort.matches("[0-9]{1,5}")) return null;
            try {
                int value = Integer.parseInt(candidatePort);
                if (value < 1 || value > 65535) return null;
            } catch (NumberFormatException ignored) {
                return null;
            }
            host = authority.substring(0, colon);
            port = ":" + candidatePort;
        }
        if ("localhost".equalsIgnoreCase(host)) return null;
        try {
            String ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (!ascii.contains(".") || ascii.startsWith(".") || ascii.endsWith(".")) return null;
            return ascii + port;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static int authorityBoundary(String value, int start) {
        int boundary = value.length();
        for (char marker : new char[]{'/', '?', '#'}) {
            int found = value.indexOf(marker, start);
            if (found >= 0 && found < boundary) boundary = found;
        }
        return boundary;
    }

    private static String validateHttps(String value) {
        URI candidate = parse(value);
        if (candidate == null || candidate.getHost() == null || candidate.getRawUserInfo() != null) return null;
        if (!"https".equalsIgnoreCase(candidate.getScheme())) return null;
        return candidate.toASCIIString();
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
