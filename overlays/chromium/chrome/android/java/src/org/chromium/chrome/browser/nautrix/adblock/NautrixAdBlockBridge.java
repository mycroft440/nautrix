package org.chromium.chrome.browser.nautrix.adblock;

/** Java entry point for replacing the native adblock-rust rules atomically. */
public final class NautrixAdBlockBridge {
    public static boolean replaceRules(String rules) {
        if (rules == null || rules.isBlank()) return false;
        try { return nativeReplaceRules(rules); }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    private static native boolean nativeReplaceRules(String rules);
    private NautrixAdBlockBridge() {}
}
