package com.cappielloantonio.tempo.util

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.plex.api.server.ServerAddressBook
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Pins the placement ServerAddressResolver depends on: DynamicMediaSourceFactory
 * wraps every DataSource.Factory it builds in a ResolvingDataSource.Factory,
 * regardless of which branch (streaming cache on or off) produced it. See the
 * comment on the wrap in createMediaSource -- a resolver added only inside
 * DownloadUtil.getCacheDataSourceFactory would silently not run for anyone who
 * set the streaming cache to zero, which is exactly the case
 * theResolverWrapsWhenTheStreamingCacheIsOff pins.
 *
 * Asserted by reflecting into the real ProgressiveMediaSource ExoPlayer will
 * use to load bytes, rather than on DynamicMediaSourceFactory's own return
 * type: that is what "address resolution happens on both branches" actually
 * means to a caller, and it is a type check (wrapped vs. unwrapped
 * DataSource.Factory) because reaching a resolved DataSpec would mean
 * actually opening a connection, which is real network I/O this test cannot
 * do.
 *
 * Robolectric: DownloadUtil.getUpstreamDataSourceFactory/getCacheDataSourceFactory
 * both need a real Context, and Preferences reads App.getInstance().preferences,
 * which Robolectric caches statically across test methods -- the streaming
 * cache size preference this test sets is reset in @Before rather than
 * assumed, and restored in @After so it does not leak into another suite.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class DynamicMediaSourceFactoryTest {

    private lateinit var factory: DynamicMediaSourceFactory

    @Before
    fun setUp() {
        factory = DynamicMediaSourceFactory(RuntimeEnvironment.getApplication())
        setStreamingCacheSize(DEFAULT_STREAMING_CACHE_SIZE)

        // DynamicMediaSourceFactory wraps ServerAddressBook.shared in
        // production, so createMediaSource touches the singleton even though
        // this test never resolves a DataSpec through it -- resetForTest()
        // is the documented way to keep that from leaking a failure cooldown
        // into another suite.
        ServerAddressBook.shared.resetForTest()
    }

    @After
    fun tearDown() {
        setStreamingCacheSize(DEFAULT_STREAMING_CACHE_SIZE)
    }

    private fun setStreamingCacheSize(megabytes: Long) {
        App.getInstance().preferences.edit()
            .putString("streaming_cache_size", megabytes.toString())
            .commit()
    }

    private fun mediaItem() =
        MediaItem.Builder().setUri(Uri.parse("https://dead.example/library/parts/1/2/file.mp3")).build()

    /**
     * ProgressiveMediaSource.Factory stores the DataSource.Factory it is given
     * without further wrapping, and passes it straight through to the
     * ProgressiveMediaSource it builds -- confirmed against the 1.9.2 jar --
     * so reflecting on that private field recovers exactly what
     * DynamicMediaSourceFactory built.
     */
    private fun dataSourceFactoryOf(mediaSource: MediaSource): DataSource.Factory {
        val field = ProgressiveMediaSource::class.java.getDeclaredField("dataSourceFactory")
        field.isAccessible = true
        return field.get(mediaSource) as DataSource.Factory
    }

    @Test
    fun theResolverWrapsWhenTheStreamingCacheIsOff() {
        setStreamingCacheSize(0L)

        val mediaSource = factory.createMediaSource(mediaItem())

        assertTrue(dataSourceFactoryOf(mediaSource) is ResolvingDataSource.Factory)
    }

    @Test
    fun theResolverWrapsWhenTheStreamingCacheIsOn() {
        setStreamingCacheSize(256L)

        val mediaSource = factory.createMediaSource(mediaItem())

        assertTrue(dataSourceFactoryOf(mediaSource) is ResolvingDataSource.Factory)
    }

    companion object {
        private const val DEFAULT_STREAMING_CACHE_SIZE = 256L
    }
}
