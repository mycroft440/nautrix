package org.chromium.chrome.browser.privacy.secure_dns;

import org.chromium.net.SecureDnsMode;

/** Small public facade over Chromium's package-private SecureDnsBridge. */
public final class NautrixSecureDnsApi {
    public static boolean applySecureConfig(String config) {
        if (SecureDnsBridge.isModeManaged()) return false;
        if (!SecureDnsBridge.setConfig(config)) return false;
        SecureDnsBridge.setMode(SecureDnsMode.SECURE);
        return true;
    }

    public static boolean applySystemResolver() {
        if (SecureDnsBridge.isModeManaged()) return false;
        SecureDnsBridge.setConfig("");
        SecureDnsBridge.setMode(SecureDnsMode.OFF);
        return true;
    }

    public static boolean applyChromiumAutomatic() {
        if (SecureDnsBridge.isModeManaged()) return false;
        SecureDnsBridge.setConfig("");
        SecureDnsBridge.setMode(SecureDnsMode.AUTOMATIC);
        return true;
    }

    public static boolean probe(String config) { return SecureDnsBridge.probeConfig(config); }
    public static String currentConfig() { return SecureDnsBridge.getConfig(); }
    public static int currentMode() { return SecureDnsBridge.getMode(); }
    public static boolean isManaged() { return SecureDnsBridge.isModeManaged(); }

    private NautrixSecureDnsApi() {}
}
