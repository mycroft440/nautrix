package com.nautrix.browser;

import android.content.Context;

import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableSet;

/** Persistent on-play video cache with a hard storage cap and best-effort age retention. */
public final class VideoCache {
    public static final long MIN_RETENTION_MS = 5L * 24L * 60L * 60L * 1_000L;
    private static final long NORMAL_RETENTION_MS = 7L * 24L * 60L * 60L * 1_000L;
    private static final long TARGET_BYTES = 1_024L * 1_024L * 1_024L;
    private static VideoCache instance;

    private final SimpleCache cache;

    public static synchronized VideoCache get(Context context) {
        if (instance == null) instance = new VideoCache(context.getApplicationContext());
        return instance;
    }

    private VideoCache(Context context) {
        File directory = new File(context.getFilesDir(), "video_media_cache");
        cache = new SimpleCache(directory, new LeastRecentlyUsedCacheEvictor(TARGET_BYTES),
                new StandaloneDatabaseProvider(context));
        Thread cleanup = new Thread(this::removeExpiredSpans, "nautrix-video-cache-cleanup");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    public DataSource.Factory dataSourceFactory(DataSource.Factory upstream) {
        return new CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    public long sizeBytes() {
        return cache.getCacheSpace();
    }

    public void clearAsync(Runnable completion) {
        new Thread(() -> {
            try {
                for (String key : new ArrayList<>(cache.getKeys())) cache.removeResource(key);
            } catch (Exception ignored) {
            }
            if (completion != null) completion.run();
        }, "nautrix-video-cache-clear").start();
    }

    private void removeExpiredSpans() {
        try {
            long now = System.currentTimeMillis();
            List<CacheSpan> eligible = new ArrayList<>();
            for (String key : cache.getKeys()) {
                NavigableSet<CacheSpan> spans = cache.getCachedSpans(key);
                for (CacheSpan span : spans) {
                    long age = now - span.lastTouchTimestamp;
                    if (age >= NORMAL_RETENTION_MS) eligible.add(span);
                }
            }
            eligible.sort(Comparator.comparingLong(span -> span.lastTouchTimestamp));
            for (CacheSpan span : eligible) cache.removeSpan(span);
        } catch (Exception ignored) {
        }
    }
}
