package com.nautrix.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.WebResourceRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JNI bridge to Brave's adblock-rust engine. EasyList and EasyPrivacy are cached locally and
 * refreshed at most once per week. A conservative Java denylist remains active while native rules
 * load or if an ABI-specific native library is unavailable.
 */
public final class AdBlockEngine {
    private static final String EASYLIST = "https://easylist.to/easylist/easylist.txt";
    private static final String EASYPRIVACY = "https://easylist.to/easylist/easyprivacy.txt";
    private static final long UPDATE_INTERVAL_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final int MAX_LIST_BYTES = 12 * 1024 * 1024;
    private static final int MAX_COSMETIC_SELECTORS = 1500;
    private static final int MAX_COSMETIC_CSS_CHARS = 180_000;
    private static final boolean LIBRARY_LOADED;

    static {
        boolean loaded;
        try {
            System.loadLibrary("nautrix_adblock");
            loaded = true;
        } catch (UnsatisfiedLinkError error) {
            loaded = false;
        }
        LIBRARY_LOADED = loaded;
    }

    private final Context context;
    private final SharedPreferences preferences;
    private final TrackerBlocker fallback = new TrackerBlocker();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean nativeReady;
    private volatile Set<String> disabledHosts;

    public AdBlockEngine(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences("nautrix", Context.MODE_PRIVATE);
        disabledHosts = new HashSet<>(preferences.getStringSet(
                "adblock_disabled_hosts", Collections.emptySet()));
    }

    public void initialize() {
        worker.execute(() -> {
            if (LIBRARY_LOADED) {
                String cached = readCachedRules();
                String initial = cached == null ? readStarterRules() : cached;
                nativeReady = initial != null && nativeReplaceRules(initial);
            }
            long updatedAt = preferences.getLong("adblock_updated_at", 0L);
            if (System.currentTimeMillis() - updatedAt >= UPDATE_INTERVAL_MS) refreshRules();
        });
    }

    public boolean shouldBlock(WebResourceRequest request, String topLevelUrl) {
        if (request == null || request.isForMainFrame() || !isEnabledForUrl(topLevelUrl)) return false;
        String requestUrl = request.getUrl().toString();
        if (nativeReady) {
            String source = topLevelUrl == null || topLevelUrl.isEmpty() ? requestUrl : topLevelUrl;
            try {
                if (nativeShouldBlock(requestUrl, source, resourceType(request))) return true;
            } catch (UnsatisfiedLinkError ignored) {
                nativeReady = false;
            }
        }
        return fallback.shouldBlock(request);
    }

    public String cosmeticScriptFor(String url) {
        if (!nativeReady || !isEnabledForUrl(url) || url == null || !url.startsWith("https://")) {
            return null;
        }
        try {
            String raw = nativeCosmeticResources(url);
            if (raw == null || raw.isEmpty()) return null;
            JSONArray selectors = new JSONObject(raw).optJSONArray("hide_selectors");
            if (selectors == null || selectors.length() == 0) return null;
            StringBuilder css = new StringBuilder(Math.min(MAX_COSMETIC_CSS_CHARS, 32_000));
            int count = Math.min(selectors.length(), MAX_COSMETIC_SELECTORS);
            for (int i = 0; i < count && css.length() < MAX_COSMETIC_CSS_CHARS; i++) {
                String selector = selectors.optString(i, "").trim();
                if (!selector.isEmpty()) css.append(selector).append("{display:none!important;}\n");
            }
            if (css.length() == 0) return null;
            return "(function(){var s=document.getElementById('nautrix-adblock-css');"
                    + "if(!s){s=document.createElement('style');s.id='nautrix-adblock-css';"
                    + "(document.head||document.documentElement).appendChild(s);}s.textContent="
                    + JSONObject.quote(css.toString()) + ";})();";
        } catch (Exception ignored) {
            return null;
        }
    }

    public boolean isNativeReady() {
        return nativeReady;
    }

    public boolean isEnabledForUrl(String url) {
        String host = hostOf(url);
        return host == null || !disabledHosts.contains(host);
    }

    public void setEnabledForUrl(String url, boolean enabled) {
        String host = hostOf(url);
        if (host == null) return;
        Set<String> changed = new HashSet<>(disabledHosts);
        if (enabled) changed.remove(host);
        else changed.add(host);
        disabledHosts = changed;
        preferences.edit().putStringSet("adblock_disabled_hosts", changed).apply();
    }

    public void close() {
        worker.shutdownNow();
    }

    private void refreshRules() {
        if (!LIBRARY_LOADED) return;
        try {
            String easyList = download(EASYLIST);
            String easyPrivacy = download(EASYPRIVACY);
            String combined = easyList + "\n" + easyPrivacy;
            if (!nativeReplaceRules(combined)) return;
            nativeReady = true;
            File directory = new File(context.getFilesDir(), "adblock");
            if (!directory.exists() && !directory.mkdirs()) return;
            File temporary = new File(directory, "filters.tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(combined.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            File destination = new File(directory, "filters.txt");
            if (destination.exists() && !destination.delete()) return;
            if (temporary.renameTo(destination)) {
                preferences.edit().putLong("adblock_updated_at", System.currentTimeMillis()).apply();
            }
        } catch (Exception ignored) {
            // Offline and transient failures keep the last known-good rules active.
        }
    }

    private String download(String source) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(source).openConnection();
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(25_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Nautrix/0.1 Android adblock updater");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status);
            int declared = connection.getContentLength();
            if (declared > MAX_LIST_BYTES) throw new IllegalStateException("Filter list too large");
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 ByteArrayOutputStream output = new ByteArrayOutputStream(
                         declared > 0 ? Math.min(declared, MAX_LIST_BYTES) : 64 * 1024)) {
                byte[] buffer = new byte[16 * 1024];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    total += read;
                    if (total > MAX_LIST_BYTES) throw new IllegalStateException("Filter list too large");
                    output.write(buffer, 0, read);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            connection.disconnect();
        }
    }

    private String readCachedRules() {
        File file = new File(new File(context.getFilesDir(), "adblock"), "filters.txt");
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_LIST_BYTES * 2L) return null;
        try (InputStream input = new FileInputStream(file)) {
            return readText(input);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String readStarterRules() {
        try (InputStream input = context.getAssets().open("nautrix_filters.txt")) {
            return readText(input);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String readText(InputStream input) throws Exception {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) >= 0) text.append(buffer, 0, read);
        }
        return text.toString();
    }

    private static String resourceType(WebResourceRequest request) {
        String accept = request.getRequestHeaders().get("Accept");
        String value = accept == null ? "" : accept.toLowerCase(Locale.ROOT);
        String path = request.getUrl().getPath();
        String lowerPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (value.contains("text/css") || lowerPath.endsWith(".css")) return "stylesheet";
        if (value.contains("javascript") || lowerPath.endsWith(".js")) return "script";
        if (value.startsWith("image/") || lowerPath.matches(".*\\.(png|jpe?g|gif|webp|svg|avif)$")) {
            return "image";
        }
        if (value.startsWith("video/") || value.startsWith("audio/")) return "media";
        if (value.contains("font/") || lowerPath.matches(".*\\.(woff2?|ttf|otf)$")) return "font";
        if (value.contains("json") || value.contains("xml")) return "xmlhttprequest";
        return "other";
    }

    private static String hostOf(String url) {
        if (url == null) return null;
        try {
            String host = Uri.parse(url).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static native boolean nativeReplaceRules(String rules);
    private static native boolean nativeShouldBlock(String url, String sourceUrl, String resourceType);
    private static native String nativeCosmeticResources(String url);
}
