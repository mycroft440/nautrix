package org.chromium.chrome.browser.nautrix.policy;

/** Pure decision logic for Nautrix automatic tab lifecycle. */
public final class TabPolicy {
    public static final class State {
        public final long createdAtMs;
        public final long lastAccessMs;
        public final boolean used;
        public final boolean pinned;
        public final boolean activeMedia;
        public final boolean activeDownload;
        public final boolean dirtyForm;

        public State(long createdAtMs, long lastAccessMs, boolean used, boolean pinned,
                boolean activeMedia, boolean activeDownload, boolean dirtyForm) {
            this.createdAtMs = createdAtMs;
            this.lastAccessMs = lastAccessMs;
            this.used = used;
            this.pinned = pinned;
            this.activeMedia = activeMedia;
            this.activeDownload = activeDownload;
            this.dirtyForm = dirtyForm;
        }
    }

    public static boolean isProtected(State s) {
        return s.pinned || s.activeMedia || s.activeDownload || s.dirtyForm;
    }

    public static long deadlineMs(State s) {
        long base = s.used ? Math.max(s.createdAtMs, s.lastAccessMs) : s.createdAtMs;
        return base + (s.used ? NautrixConstants.USED_TAB_TTL_MS
                             : NautrixConstants.NEVER_USED_TAB_TTL_MS);
    }

    public static boolean shouldAutoClose(long nowMs, State s) {
        return !isProtected(s) && nowMs >= deadlineMs(s);
    }

    private TabPolicy() {}
}
