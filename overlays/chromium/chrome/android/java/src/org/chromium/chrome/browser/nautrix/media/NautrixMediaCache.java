package org.chromium.chrome.browser.nautrix.media;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheKeyFactory;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.ContentMetadata;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.datasource.DefaultHttpDataSource;

import org.chromium.chrome.browser.nautrix.policy.MediaCachePolicy;
import org.chromium.chrome.browser.nautrix.policy.NautrixConstants;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Persistent media cache separate from Chromium's ordinary HTTP cache. */
@UnstableApi
public final class NautrixMediaCache {
    private static NautrixMediaCache instance;
    private final SimpleCache cache;
    private final SharedPreferences index;
    private final DataSource.Factory dataSourceFactory;
    private final CacheDataSource.Factory cacheDataSourceFactory;
    private final ConcurrentHashMap<String, Long> recentTouches = new ConcurrentHashMap<>();
    private volatile String activeGroupKey;
    private static final long TOUCH_WRITE_INTERVAL_MS = 60_000L;

    private NautrixMediaCache(Context context) {
        Context app = context.getApplicationContext();
        File dir = new File(app.getFilesDir(), "nautrix_media_cache");
        // Nautrix owns eviction. A generic LRU cannot know that a fully cached movie should
        // survive ahead of old partial fragments, nor can it honor the user's Keep action.
        cache = new SimpleCache(dir, new NoOpCacheEvictor(), new StandaloneDatabaseProvider(app));
        index = app.getSharedPreferences("nautrix_media_cache_index", Context.MODE_PRIVATE);
        cacheDataSourceFactory = new CacheDataSource.Factory()
                .setCache(cache)
                .setCacheKeyFactory(CacheKeyFactory.DEFAULT)
                .setUpstreamDataSourceFactory(new DefaultHttpDataSource.Factory()
                        .setUserAgent("Nautrix"))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        dataSourceFactory = () -> new TouchingDataSource(cacheDataSourceFactory.createDataSource());
        cleanupExpired();
    }

    public static synchronized NautrixMediaCache get(Context context) {
        if (instance == null) instance = new NautrixMediaCache(context);
        return instance;
    }

    public DataSource.Factory dataSourceFactory() { return dataSourceFactory; }
    /** For permanent-download promotion: reads Smart Cache without playback grouping side effects. */
    public DataSource.Factory promotionUpstreamFactory() { return cacheDataSourceFactory; }
    public SimpleCache cache() { return cache; }

    /** Records access for direct files as well as every HLS/DASH manifest/segment key. */
    public void touch(String key) {
        if (key == null || key.isEmpty()) return;
        long now = System.currentTimeMillis();
        Long previous = recentTouches.put(key, now);
        if (previous != null && now - previous < TOUCH_WRITE_INTERVAL_MS) return;
        index.edit().putLong("access:" + key, now).putBoolean("known:" + key, true).apply();
    }

    /** Associates subsequent HLS/DASH manifest/segment reads with the currently playing item. */
    public void setActiveMediaKey(@Nullable String key) { activeGroupKey = key; }

    /** Keep means persistent until the user explicitly releases it or clears app data. */
    public synchronized void keepOffline(String key, boolean keep) {
        if (key == null || key.isEmpty()) return;
        SharedPreferences.Editor editor = index.edit().putBoolean("pin:" + key, keep);
        for (String cachedKey : cache.getKeys()) {
            if (key.equals(index.getString("group:" + cachedKey, null))) {
                editor.putBoolean("pin:" + cachedKey, keep);
            }
        }
        editor.apply();
        if (keep) touch(key);
    }

    public void setPinned(String key, boolean pinned) { keepOffline(key, pinned); }

    public boolean isComplete(String key) {
        long length = ContentMetadata.getContentLength(cache.getContentMetadata(key));
        if (length == C.LENGTH_UNSET || length <= 0) return false;
        return cache.getCachedBytes(key, 0, length) >= length;
    }

    public int percentCached(String key) {
        long length = ContentMetadata.getContentLength(cache.getContentMetadata(key));
        if (length == C.LENGTH_UNSET || length <= 0) return 0;
        return (int) Math.min(100L, (cache.getCachedBytes(key, 0, length) * 100L) / length);
    }

    /**
     * Enforces the 5-day TTL and storage budget. Expired partial entries go first; complete and
     * recent entries receive a retention bonus; kept entries are never automatically removed.
     */
    public synchronized void cleanupExpired() {
        long now = System.currentTimeMillis();
        Set<String> keys = new HashSet<>(cache.getKeys());
        SharedPreferences.Editor editor = index.edit();
        for (String key : keys) {
            long access = index.getLong("access:" + key, 0L);
            boolean pinned = index.getBoolean("pin:" + key, false);
            if (MediaCachePolicy.isExpired(now, access, pinned, false)) {
                removeKey(key, editor);
            }
        }
        editor.apply();
        enforceBudget(now);
    }

    private void enforceBudget(long now) {
        long budget = index.getLong("budget_bytes", NautrixConstants.DEFAULT_MEDIA_CACHE_BYTES);
        if (budget <= 0 || cache.getCacheSpace() <= budget) return;
        List<String> candidates = new ArrayList<>(cache.getKeys());
        candidates.removeIf(key -> index.getBoolean("pin:" + key, false));
        candidates.sort((a, b) -> Long.compare(retentionScore(now, a), retentionScore(now, b)));
        SharedPreferences.Editor editor = index.edit();
        for (String key : candidates) {
            if (cache.getCacheSpace() <= budget) break;
            removeKey(key, editor);
        }
        editor.apply();
    }

    private long retentionScore(long now, String key) {
        return MediaCachePolicy.retentionScore(now, index.getLong("access:" + key, 0L),
                isComplete(key), index.getBoolean("pin:" + key, false), false);
    }

    private void removeKey(String key, SharedPreferences.Editor editor) {
        try { cache.removeResource(key); } catch (Exception ignored) {}
        editor.remove("access:" + key).remove("known:" + key).remove("pin:" + key)
                .remove("group:" + key);
    }

    private final class TouchingDataSource implements DataSource {
        private final DataSource delegate;
        private String openedKey;

        TouchingDataSource(DataSource delegate) { this.delegate = delegate; }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            openedKey = CacheKeyFactory.DEFAULT.buildCacheKey(dataSpec);
            touch(openedKey);
            String group = activeGroupKey;
            if (group != null && !group.isEmpty() && !group.equals(openedKey)) {
                index.edit().putString("group:" + openedKey, group).apply();
                if (index.getBoolean("pin:" + group, false)) {
                    index.edit().putBoolean("pin:" + openedKey, true).apply();
                }
            }
            return delegate.open(dataSpec);
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0 && openedKey != null) touch(openedKey);
            return read;
        }

        @Override public @Nullable Uri getUri() { return delegate.getUri(); }
        @Override public void addTransferListener(TransferListener transferListener) {
            delegate.addTransferListener(transferListener);
        }
        @Override public void close() throws IOException { delegate.close(); openedKey = null; }
    }
}
