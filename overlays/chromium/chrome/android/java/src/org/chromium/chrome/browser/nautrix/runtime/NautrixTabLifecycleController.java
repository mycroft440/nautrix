package org.chromium.chrome.browser.nautrix.runtime;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.chromium.chrome.browser.nautrix.download.NautrixDownloadProtectionBridge;
import org.chromium.chrome.browser.nautrix.policy.NautrixConstants;
import org.chromium.chrome.browser.nautrix.policy.TabPolicy;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabCreationState;
import org.chromium.chrome.browser.tab.TabLaunchType;
import org.chromium.chrome.browser.tab.TabSelectionType;
import org.chromium.chrome.browser.tabmodel.TabClosureParams;
import org.chromium.chrome.browser.tabmodel.TabClosingSource;
import org.chromium.chrome.browser.tabmodel.TabModel;
import org.chromium.chrome.browser.tabmodel.TabModelObserver;
import org.chromium.chrome.browser.tabmodel.TabModelSelector;

import java.util.ArrayList;
import java.util.List;

/** Enforces 1-hour never-used and 5-day used-tab retention without keeping renderers alive. */
final class NautrixTabLifecycleController implements TabModelObserver {
    private static final String PREFS = "nautrix_tab_lifecycle";
    private final TabModelSelector selector;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean destroyed;

    NautrixTabLifecycleController(Context context, TabModelSelector selector) {
        this.selector = selector;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void start() {
        selector.addObserverToAllModels(this);
        initializeRestoredTabs();
        sweep();
    }

    void destroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        selector.removeObserverFromAllModels(this);
    }

    @Override
    public void didAddTab(Tab tab, @TabLaunchType int type, @TabCreationState int creationState,
            boolean markedForSelection) {
        if (isPrivate(tab)) return;
        long now = System.currentTimeMillis();
        prefs.edit().putLong(createdKey(tab), now).putLong(lastKey(tab), markedForSelection ? now : 0L)
                .putBoolean(usedKey(tab), markedForSelection).apply();
    }

    @Override
    public void didSelectTab(Tab tab, @TabSelectionType int type, int lastId) {
        if (isPrivate(tab)) return;
        markUsed(tab, System.currentTimeMillis());
    }

    @Override
    public void tabRemoved(Tab tab) { forget(tab); }

    @Override
    public void onTabCloseCommitted(List<Tab> tabs, boolean isAllTabs, boolean canRestore,
            @TabClosingSource int closingSource) {
        for (Tab tab : tabs) forget(tab);
    }

    private void initializeRestoredTabs() {
        long now = System.currentTimeMillis();
        for (TabModel model : selector.getModels()) {
            if (model.getProfile() != null && model.getProfile().isOffTheRecord()) continue;
            for (int i = 0; i < model.getCount(); i++) {
                Tab tab = model.getTabAt(i);
                if (tab == null || prefs.contains(createdKey(tab))) continue;
                long timestamp = tab.getTimestampMillis();
                long last = timestamp > 0 ? timestamp : now;
                // Restored tabs existed in a previous session, so never classify them as brand-new.
                prefs.edit().putLong(createdKey(tab), last).putLong(lastKey(tab), last)
                        .putBoolean(usedKey(tab), true).apply();
            }
        }
    }

    private void sweep() {
        if (destroyed) return;
        long now = System.currentTimeMillis();
        for (TabModel model : selector.getModels()) sweepModel(model, now);
        handler.postDelayed(this::sweep, NautrixConstants.TAB_SWEEP_INTERVAL_MS);
    }

    private void sweepModel(TabModel model, long now) {
        if (model.getProfile() != null && model.getProfile().isOffTheRecord()) return;
        List<Tab> stale = new ArrayList<>();
        for (int i = 0; i < model.getCount(); i++) {
            Tab tab = model.getTabAt(i);
            if (tab == null || tab.isClosing() || tab.isDestroyed()) continue;
            ensureMetadata(tab, now);
            TabPolicy.State state = new TabPolicy.State(
                    prefs.getLong(createdKey(tab), now), prefs.getLong(lastKey(tab), 0L),
                    prefs.getBoolean(usedKey(tab), true), tab.isPinned(), tab.getMediaState() != 0,
                    (NautrixDownloadProtectionBridge.hasActiveDownload(tab)
                            || NautrixTabProtectionRegistry.hasActiveDownload(tab.getId())),
                    (NautrixDownloadProtectionBridge.hasPotentialUnsavedState(tab)
                            || NautrixTabProtectionRegistry.hasDirtyForm(tab.getId())));
            if (TabPolicy.shouldAutoClose(now, state)) stale.add(tab);
        }
        if (!stale.isEmpty()) {
            TabClosureParams params = TabClosureParams.closeTabs(stale)
                    .allowUndo(true).saveToTabRestoreService(true)
                    .tabClosingSource(TabClosingSource.UNKNOWN).build();
            model.getTabRemover().closeTabs(params, /* allowDialog= */ false);
        }
    }

    private void ensureMetadata(Tab tab, long now) {
        if (prefs.contains(createdKey(tab))) return;
        prefs.edit().putLong(createdKey(tab), now).putLong(lastKey(tab), now)
                .putBoolean(usedKey(tab), true).apply();
    }

    private void markUsed(Tab tab, long now) {
        ensureMetadata(tab, now);
        prefs.edit().putBoolean(usedKey(tab), true).putLong(lastKey(tab), now).apply();
        tab.setTimestampMillis(now);
    }

    private void forget(Tab tab) {
        if (!isPrivate(tab)) prefs.edit().remove(createdKey(tab)).remove(lastKey(tab)).remove(usedKey(tab)).apply();
        NautrixTabProtectionRegistry.forget(tab.getId());
    }

    private static boolean isPrivate(Tab tab) { return tab.getProfile().isOffTheRecord(); }
    private static String createdKey(Tab t) { return "created_" + t.getId(); }
    private static String lastKey(Tab t) { return "last_" + t.getId(); }
    private static String usedKey(Tab t) { return "used_" + t.getId(); }
}
