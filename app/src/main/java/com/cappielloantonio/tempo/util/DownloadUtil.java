package com.cappielloantonio.tempo.util;

import android.content.Context;

import androidx.annotation.VisibleForTesting;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.DatabaseProvider;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.SimpleCache;

import java.io.File;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@UnstableApi
public final class DownloadUtil {

    private static final String STREAMING_CACHE_CONTENT_DIRECTORY = "streaming_cache";

    private static DataSource.Factory dataSourceFactory;
    private static DataSource.Factory httpDataSourceFactory;
    private static DatabaseProvider databaseProvider;
    private static File streamingCacheDirectory;
    private static SimpleCache streamingCache;
    private static ResizableLruCacheEvictor streamingCacheEvictor;
    private static final Executor cacheResizeExecutor = Executors.newSingleThreadExecutor();

    public static boolean useExtensionRenderers() {
        return true;
    }

    public static synchronized DataSource.Factory getHttpDataSourceFactory() {
        if (httpDataSourceFactory == null) {
            CookieManager cookieManager = new CookieManager();
            cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER);
            CookieHandler.setDefault(cookieManager);
            httpDataSourceFactory =
                    new DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true);
        }

        return httpDataSourceFactory;
    }

    public static synchronized DataSource.Factory getUpstreamDataSourceFactory(Context context) {
        dataSourceFactory = new DefaultDataSource.Factory(context, getHttpDataSourceFactory());
        return dataSourceFactory;
    }

    public static synchronized DataSource.Factory getCacheDataSourceFactory(Context context) {
        CacheDataSource.Factory streamCacheFactory =
                new CacheDataSource.Factory()
                        .setCache(getStreamingCache(context))
                        .setCacheKeyFactory(new StreamingCacheKeyFactory())
                        .setUpstreamDataSourceFactory(getUpstreamDataSourceFactory(context));

        ResolvingDataSource.Factory resolvingFactory =
                new ResolvingDataSource.Factory(
                        new StreamingCacheDataSource.Factory(streamCacheFactory),
                        dataSpec -> {
                            DataSpec.Builder builder = dataSpec.buildUpon();
                            builder.setFlags(
                                    dataSpec.flags & ~DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN);
                            return builder.build();
                        });
        dataSourceFactory = resolvingFactory;
        return dataSourceFactory;
    }

    /**
     * Writes into the same streaming cache (and with the same keys) the player reads from, but
     * bypasses the download-cache/resolving wrappers of {@link
     * #getCacheDataSourceFactory(Context)}: CacheWriter needs a plain CacheDataSource.
     */
    public static synchronized CacheDataSource.Factory getStreamingCacheWriterFactory(
            Context context) {
        return new CacheDataSource.Factory()
                .setCache(getStreamingCache(context))
                .setCacheKeyFactory(new StreamingCacheKeyFactory())
                .setUpstreamDataSourceFactory(
                        new DefaultDataSource.Factory(context, getHttpDataSourceFactory()));
    }

    public static synchronized Cache getStreamingCacheForPreload(Context context) {
        return getStreamingCache(context);
    }

    /**
     * The cache's size cap in megabytes: whatever the user chose, or a share of the partition when
     * they have chosen nothing. Zero means cache nothing, and every caller able to skip the cache
     * entirely tests for it.
     *
     * <p>A change reaches a cache already built through {@link #applyStreamingCacheSize(Context)}.
     */
    public static synchronized long getStreamingCacheSizeMegabytes(Context context) {
        Long override = Preferences.getStreamingCacheSizeOverrideMegabytes();
        if (override != null) {
            return override;
        }

        return StreamingCacheSize.forDirectory(getStreamingCacheDirectory(context));
    }

    public static synchronized long getAutomaticStreamingCacheSizeMegabytes(Context context) {
        return StreamingCacheSize.forDirectory(getStreamingCacheDirectory(context));
    }

    /** Zero when the partition cannot be measured. */
    public static synchronized long getStreamingCachePartitionMegabytes(Context context) {
        return StreamingCacheSize.totalMegabytes(getStreamingCacheDirectory(context));
    }

    /**
     * Builds the cache if nothing has yet, which reads its index from disk: not on the main thread.
     *
     * <p>Deliberately not synchronized: a running resize holds the cache's monitor, and waiting on
     * it with the class lock held would stall every playback-path call.
     */
    public static long getStreamingCacheUsedBytes(Context context) {
        return getStreamingCache(context).getCacheSpace();
    }

    /**
     * Brings a cache already built to the size the preference now asks for. Lowering the size
     * deletes files, so this blocks; the UI uses {@link #applyStreamingCacheSizeInBackground}.
     */
    public static void applyStreamingCacheSize(Context context) {
        SimpleCache cache;
        ResizableLruCacheEvictor evictor;
        long bytes;
        // The resize runs outside the class lock: it deletes files under the cache's own monitor,
        // and holding this one too would stall every playback path asking for a factory.
        synchronized (DownloadUtil.class) {
            if (streamingCache == null) {
                return;
            }
            cache = streamingCache;
            evictor = streamingCacheEvictor;
            bytes = getStreamingCacheSizeMegabytes(context) * 1024 * 1024;
        }
        evictor.resize(cache, bytes);
    }

    public static void applyStreamingCacheSizeInBackground(Context context) {
        Context appContext = context.getApplicationContext();
        cacheResizeExecutor.execute(() -> applyStreamingCacheSize(appContext));
    }

    @VisibleForTesting
    static synchronized void releaseStreamingCacheForTest() {
        if (streamingCache != null) {
            streamingCache.release();
        }
        streamingCache = null;
        streamingCacheEvictor = null;
        dataSourceFactory = null;
    }

    private static synchronized SimpleCache getStreamingCache(Context context) {
        if (streamingCache == null) {
            File streamingCacheDirectory =
                    new File(
                            getStreamingCacheDirectory(context), STREAMING_CACHE_CONTENT_DIRECTORY);

            streamingCacheEvictor =
                    new ResizableLruCacheEvictor(
                            getStreamingCacheSizeMegabytes(context) * 1024 * 1024);
            streamingCache =
                    new SimpleCache(
                            streamingCacheDirectory,
                            streamingCacheEvictor,
                            getDatabaseProvider(context));
        }

        return streamingCache;
    }

    private static synchronized DatabaseProvider getDatabaseProvider(Context context) {
        if (databaseProvider == null) {
            databaseProvider = new StandaloneDatabaseProvider(context);
        }

        return databaseProvider;
    }

    private static synchronized File getStreamingCacheDirectory(Context context) {
        if (streamingCacheDirectory == null) {
            if (Preferences.getStreamingCacheStoragePreference() == 0) {
                streamingCacheDirectory = context.getExternalFilesDirs(null)[0];
                if (streamingCacheDirectory == null) {
                    streamingCacheDirectory = context.getFilesDir();
                }
            } else {
                try {
                    streamingCacheDirectory = context.getExternalFilesDirs(null)[1];
                } catch (Exception exception) {
                    streamingCacheDirectory = context.getExternalFilesDirs(null)[0];
                    Preferences.setStreamingCacheStoragePreference(0);
                }
            }
        }

        return streamingCacheDirectory;
    }
}
