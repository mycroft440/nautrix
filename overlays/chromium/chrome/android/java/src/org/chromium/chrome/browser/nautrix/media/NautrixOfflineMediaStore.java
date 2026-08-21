package org.chromium.chrome.browser.nautrix.media;

import android.content.Context;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import java.io.File;

/** Permanent offline cache. Unlike Smart Cache, it is never TTL-evicted. */
@UnstableApi
public final class NautrixOfflineMediaStore {
    private static NautrixOfflineMediaStore instance;
    private final SimpleCache cache;
    private final StandaloneDatabaseProvider databaseProvider;

    private NautrixOfflineMediaStore(Context context) {
        Context app = context.getApplicationContext();
        databaseProvider = new StandaloneDatabaseProvider(app);
        cache = new SimpleCache(new File(app.getFilesDir(), "nautrix_offline_media"),
                new NoOpCacheEvictor(), databaseProvider);
    }

    public static synchronized NautrixOfflineMediaStore get(Context context) {
        if (instance == null) instance = new NautrixOfflineMediaStore(context);
        return instance;
    }

    public SimpleCache cache() { return cache; }
    public StandaloneDatabaseProvider databaseProvider() { return databaseProvider; }

    /** Player reads permanent downloads first, then falls through to Smart Cache/network. */
    public DataSource.Factory playbackFactory(DataSource.Factory fallback) {
        return new CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(fallback)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }
}
