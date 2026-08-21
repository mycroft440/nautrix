package org.chromium.chrome.browser.nautrix.download;

import org.chromium.chrome.browser.tab.Tab;
import org.chromium.content_public.browser.WebContents;

/** Native query used by stale-tab cleanup so a tab with an active download is never closed. */
public final class NautrixDownloadProtectionBridge {
    private NautrixDownloadProtectionBridge() {}

    public static boolean hasActiveDownload(Tab tab) {
        if (tab == null) return false;
        WebContents webContents = tab.getWebContents();
        if (webContents == null || webContents.isDestroyed()) return false;
        try {
            return nativeHasActiveDownload(webContents);
        } catch (UnsatisfiedLinkError ignored) {
            // Fail safe in development builds where the native overlay is not linked yet.
            return false;
        }
    }

    /** Conservative protection for pages that registered unload/beforeunload handlers. */
    public static boolean hasPotentialUnsavedState(Tab tab) {
        if (tab == null) return false;
        WebContents webContents = tab.getWebContents();
        if (webContents == null || webContents.isDestroyed()) return false;
        try {
            return nativeHasPotentialUnsavedState(webContents);
        } catch (UnsatisfiedLinkError ignored) {
            return false;
        }
    }

    private static native boolean nativeHasActiveDownload(WebContents webContents);
    private static native boolean nativeHasPotentialUnsavedState(WebContents webContents);
}
