package org.chromium.chrome.browser.nautrix.runtime;

import org.chromium.base.ContextUtils;
import org.chromium.chrome.browser.nautrix.adblock.NautrixAdBlockBootstrap;
import org.chromium.chrome.browser.nautrix.dns.NautrixDnsAutoSelector;
import org.chromium.chrome.browser.nautrix.media.NautrixMediaCache;
import androidx.media3.common.util.UnstableApi;
import org.chromium.chrome.browser.lifecycle.ActivityLifecycleDispatcher;
import org.chromium.chrome.browser.lifecycle.DestroyObserver;
import org.chromium.chrome.browser.tabmodel.TabModelSelector;
import org.chromium.chrome.browser.tabmodel.TabModelSelectorObserver;

import java.util.Map;
import java.util.WeakHashMap;

/** Activity-scoped Nautrix runtime. Idempotent across Chromium session-restore paths. */
public final class NautrixRuntime implements DestroyObserver, TabModelSelectorObserver {
    private static final Map<TabModelSelector, NautrixRuntime> INSTANCES = new WeakHashMap<>();
    private final TabModelSelector selector;
    private final ActivityLifecycleDispatcher dispatcher;
    private NautrixTabLifecycleController tabs;
    private NautrixDnsAutoSelector dns;

    private NautrixRuntime(TabModelSelector selector, ActivityLifecycleDispatcher dispatcher) {
        this.selector = selector;
        this.dispatcher = dispatcher;
    }

    public static synchronized void attach(TabModelSelector selector,
            ActivityLifecycleDispatcher dispatcher) {
        if (INSTANCES.containsKey(selector)) return;
        NautrixRuntime runtime = new NautrixRuntime(selector, dispatcher);
        INSTANCES.put(selector, runtime);
        dispatcher.register(runtime);
        if (selector.isTabStateInitialized()) runtime.start(); else selector.addObserver(runtime);
    }

    @Override
    public void onTabStateInitialized() {
        selector.removeObserver(this);
        start();
    }

    @UnstableApi
    private void start() {
        if (tabs != null) return;
        var context = ContextUtils.getApplicationContext();
        tabs = new NautrixTabLifecycleController(context, selector);
        tabs.start();
        NautrixAdBlockBootstrap.start(context);
        dns = new NautrixDnsAutoSelector(context);
        dns.start();
        NautrixMediaCache.get(context).cleanupExpired();
    }

    @Override
    public void onDestroy() {
        selector.removeObserver(this);
        if (tabs != null) tabs.destroy();
        if (dns != null) dns.stop();
        dispatcher.unregister(this);
        synchronized (NautrixRuntime.class) { INSTANCES.remove(selector); }
    }
}
