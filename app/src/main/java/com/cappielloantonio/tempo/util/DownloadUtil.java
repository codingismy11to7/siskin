package com.cappielloantonio.tempo.util;

import android.content.Context;

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
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import java.io.File;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;

@UnstableApi
public final class DownloadUtil {

    private static final String STREAMING_CACHE_CONTENT_DIRECTORY = "streaming_cache";

    private static DataSource.Factory dataSourceFactory;
    private static DataSource.Factory httpDataSourceFactory;
    private static DatabaseProvider databaseProvider;
    private static File streamingCacheDirectory;
    private static SimpleCache streamingCache;

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
     * <p>The evictor below fixes this at construction, so a changed preference takes effect when
     * the service next starts rather than on the next track.
     */
    public static synchronized long getStreamingCacheSizeMegabytes(Context context) {
        Long override = Preferences.getStreamingCacheSizeOverrideMegabytes();
        if (override != null) {
            return override;
        }

        return StreamingCacheSize.forDirectory(getStreamingCacheDirectory(context));
    }

    private static synchronized SimpleCache getStreamingCache(Context context) {
        if (streamingCache == null) {
            File streamingCacheDirectory =
                    new File(
                            getStreamingCacheDirectory(context), STREAMING_CACHE_CONTENT_DIRECTORY);

            streamingCache =
                    new SimpleCache(
                            streamingCacheDirectory,
                            new LeastRecentlyUsedCacheEvictor(
                                    getStreamingCacheSizeMegabytes(context) * 1024 * 1024),
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
