package com.cappielloantonio.tempo.util

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import com.cappielloantonio.tempo.App
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The wiring: a changed preference reaches the evictor of the cache already built. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class StreamingCacheLiveCapTest {
    private val context get() = App.getContext()

    @Before
    fun setUp() {
        DownloadUtil.releaseStreamingCacheForTest()
        Preferences.setStreamingCacheSizeOverrideMegabytes(4L)
    }

    @After
    fun tearDown() {
        DownloadUtil.releaseStreamingCacheForTest()
        Preferences.setStreamingCacheSizeOverrideMegabytes(null)
    }

    private fun write(
        key: String,
        bytes: Int,
    ) {
        Thread.sleep(5)
        val cache = DownloadUtil.getStreamingCacheForPreload(context)
        val source = CacheDataSource(cache, ByteArrayDataSource(ByteArray(bytes)))
        val spec =
            DataSpec
                .Builder()
                .setUri(Uri.parse("https://example.invalid/$key"))
                .setKey(key)
                .build()
        CacheWriter(source, spec, null, null).cache()
    }

    @Test
    fun loweringThePreferenceTrimsTheLiveCache() {
        write("a", 1024 * 1024)
        write("b", 1024 * 1024)
        write("c", 1024 * 1024)
        assertEquals(3L * 1024 * 1024, DownloadUtil.getStreamingCacheUsedBytes(context))

        Preferences.setStreamingCacheSizeOverrideMegabytes(1L)
        DownloadUtil.applyStreamingCacheSize(context)

        assertTrue(DownloadUtil.getStreamingCacheUsedBytes(context) <= 1024L * 1024L)
    }

    @Test
    fun theUsageReadWaitsForAResizeStillQueued() {
        write("a", 1024 * 1024)
        write("b", 1024 * 1024)
        write("c", 1024 * 1024)

        Preferences.setStreamingCacheSizeOverrideMegabytes(1L)
        DownloadUtil.applyStreamingCacheSizeInBackground(context)

        assertTrue(DownloadUtil.getStreamingCacheUsedBytesAfterPendingResizes(context) <= 1024L * 1024L)
    }

    @Test
    fun offTrimsTheLiveCacheToNothing() {
        write("a", 1024 * 1024)
        assertEquals(1024L * 1024, DownloadUtil.getStreamingCacheUsedBytes(context))

        Preferences.setStreamingCacheSizeOverrideMegabytes(0L)
        DownloadUtil.applyStreamingCacheSize(context)

        assertEquals(0L, DownloadUtil.getStreamingCacheForPreload(context).cacheSpace)
    }
}
