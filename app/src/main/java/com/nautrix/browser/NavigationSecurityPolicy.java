package com.nautrix.browser;

import java.net.URI;
import java.util.Locale;

/** Pure URL policy shared by deep links, external intents and sensitive request headers. */
public final class NavigationSecurityPolicy {
    private NavigationSecurityPolicy() { }

    public static boolean mayLaunchExternal(boolean mainFrame, boolean hasUserGesture) {
        return mainFrame && hasUserGesture;
    }

    public static String safeHttpsUrl(String raw) {
        URI uri = parse(raw);
        if (!isHttpsHost(uri) || uri.getRawUserInfo() != null) return null;
        return uri.toASCIIString();
    }

    public static String upgradeHttpToHttps(String raw) {
        URI uri = parse(raw);
        if (uri == null || uri.getHost() == null || uri.getRawUserInfo() != null) return null;
        String scheme = uri.getScheme();
        if (scheme == null) return null;
        if ("https".equalsIgnoreCase(scheme)) return safeHttpsUrl(raw);
        if (!"http".equalsIgnoreCase(scheme)) return null;
        try {
            String authority = uri.getRawAuthority();
            if (uri.getPort() == 80) {
                authority = uri.getHost().indexOf(':') >= 0
                        ? "[" + uri.getHost() + "]" : uri.getHost();
            }
            StringBuilder upgraded = new StringBuilder("https://").append(authority);
            if (uri.getRawPath() != null) upgraded.append(uri.getRawPath());
            if (uri.getRawQuery() != null) upgraded.append('?').append(uri.getRawQuery());
            if (uri.getRawFragment() != null) upgraded.append('#').append(uri.getRawFragment());
            return URI.create(upgraded.toString()).toASCIIString();
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String originOnly(String raw) {
        URI uri = parse(raw);
        if (!isHttpsHost(uri) || uri.getRawUserInfo() != null) return null;
        try {
            int port = uri.getPort() == 443 ? -1 : uri.getPort();
            return new URI("https", null, uri.getHost(), port, "/", null, null).toASCIIString();
        } catch (Exception ignored) {
            return null;
        }
    }

    public static boolean sameHttpsOrigin(String first, String second) {
        URI left = parse(first);
        URI right = parse(second);
        if (!isHttpsHost(left) || !isHttpsHost(right)) return false;
        return left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static boolean isHttpsHost(URI uri) {
        return uri != null && uri.getScheme() != null && uri.getHost() != null
                && "https".equals(uri.getScheme().toLowerCase(Locale.ROOT));
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() < 0 ? 443 : uri.getPort();
    }

    private static URI parse(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) return null;
        try {
            return URI.create(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
