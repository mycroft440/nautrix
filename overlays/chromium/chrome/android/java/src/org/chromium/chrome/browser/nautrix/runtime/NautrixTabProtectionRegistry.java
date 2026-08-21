package org.chromium.chrome.browser.nautrix.runtime;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Cross-feature protection flags consulted by automatic tab cleanup. */
public final class NautrixTabProtectionRegistry {
    private static final Set<Integer> DOWNLOADS = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> DIRTY_FORMS = ConcurrentHashMap.newKeySet();

    public static void setActiveDownload(int tabId, boolean active) { set(DOWNLOADS, tabId, active); }
    public static void setDirtyForm(int tabId, boolean dirty) { set(DIRTY_FORMS, tabId, dirty); }
    public static boolean hasActiveDownload(int tabId) { return DOWNLOADS.contains(tabId); }
    public static boolean hasDirtyForm(int tabId) { return DIRTY_FORMS.contains(tabId); }
    public static void forget(int tabId) { DOWNLOADS.remove(tabId); DIRTY_FORMS.remove(tabId); }

    private static void set(Set<Integer> set, int id, boolean enabled) {
        if (enabled) set.add(id); else set.remove(id);
    }

    private NautrixTabProtectionRegistry() {}
}
