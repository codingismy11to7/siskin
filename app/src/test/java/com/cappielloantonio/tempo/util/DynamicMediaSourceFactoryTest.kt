package com.cappielloantonio.tempo.util

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.SectionKey
import com.cappielloantonio.tempo.plex.api.server.ServerAddressBook
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

/**
 * Pins the placement ServerAddressResolver depends on: DynamicMediaSourceFactory
 * wraps every DataSource.Factory it builds in a ResolvingDataSource.Factory,
 * regardless of which branch (streaming cache on or off) produced it. See the
 * comment on buildDataSourceFactory -- a resolver added only inside
 * DownloadUtil.getCacheDataSourceFactory would silently not run for anyone who
 * set the streaming cache to zero, which is exactly the case
 * theResolverWrapsWhenTheStreamingCacheIsOff pins.
 *
 * Both tests open a real DataSpec, built against a dead local port, through
 * the exact DataSource.Factory buildDataSourceFactory() hands to
 * createMediaSource, and assert the request actually landed on a MockWebServer
 * standing in for the live server -- MockWebServer is already a project
 * dependency, used exactly this way throughout ServerAddressBookTest and
 * ServerAddressResolverTest, so "reaching a resolved DataSpec would mean real
 * network I/O this test cannot do" (this file's previous justification for a
 * type check instead) was wrong.
 *
 * The type check it replaced was worse than merely weaker: on the
 * streaming-cache-on branch it was vacuous. DownloadUtil.getCacheDataSourceFactory
 * itself returns a ResolvingDataSource.Factory -- the flag-fixer at
 * DownloadUtil.java:62-71 that clears FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN -- so
 * `is ResolvingDataSource.Factory` on that branch was satisfied by the
 * unwrapped cache factory whether or not ServerAddressResolver was ever
 * wrapped around it. Deleting the wrap in buildDataSourceFactory entirely
 * (`val dataSourceFactory: DataSource.Factory = selected`) proves it: the
 * request stays aimed at the dead port and the mock never sees it, on both
 * branches, which is what these two tests now actually assert against.
 *
 * Robolectric: DownloadUtil.getUpstreamDataSourceFactory/getCacheDataSourceFactory
 * both need a real Context, and Preferences/PlexApi read
 * App.getInstance().preferences, which Robolectric caches statically across
 * test methods -- the streaming cache size preference and the PlexApi session
 * this test sets are both reset in @Before rather than assumed, and restored
 * in @After so neither leaks into another suite. DynamicMediaSourceFactory
 * also reads ServerAddressBook.shared in production, so resetForTest() clears
 * its failure cooldown for the same reason.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class DynamicMediaSourceFactoryTest {

    private lateinit var factory: DynamicMediaSourceFactory
    private lateinit var api: PlexApi
    private lateinit var liveServer: MockWebServer

    @Before
    fun setUp() {
        factory = DynamicMediaSourceFactory(RuntimeEnvironment.getApplication())
        setStreamingCacheSize(DEFAULT_STREAMING_CACHE_SIZE)

        // DynamicMediaSourceFactory wraps ServerAddressBook.shared in
        // production, so buildDataSourceFactory touches the singleton --
        // resetForTest() is the documented way to keep a failure cooldown
        // from a previous test leaking into this one, or from this one
        // leaking into another suite.
        ServerAddressBook.shared.resetForTest()

        api = PlexApi()
        api.accountToken = "account-token"
        api.serverCandidates = null

        liveServer = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(200).setBody("stream-bytes"))
            start()
        }
        // ServerAddressBook.shared reads PlexApi.session through the same
        // SharedPreferences PlexApi wraps here -- PlexApi holds no state of
        // its own -- so pointing this session at the mock is what the
        // resolver inside buildDataSourceFactory() sees.
        api.session = PlexSession(
            accountToken = "account-token",
            serverUri = liveServer.url("/").toString().trimEnd('/'),
            musicSectionKey = SectionKey("5"),
            serverToken = "server-token",
            machineIdentifier = "machine-a"
        )
    }

    @After
    fun tearDown() {
        setStreamingCacheSize(DEFAULT_STREAMING_CACHE_SIZE)
        liveServer.shutdown()
        api.session = null
        api.accountToken = null
        api.serverCandidates = null
    }

    private fun setStreamingCacheSize(megabytes: Long) {
        App.getInstance().preferences.edit()
            .putString("streaming_cache_size", megabytes.toString())
            .commit()
    }

    /**
     * A real, closed local port: started and immediately shut down, so a
     * connection attempt against it fails fast and deterministically
     * (connection refused) rather than depending on DNS behaviour for a
     * fictitious host, which can be slow or environment-dependent. Same
     * technique as ServerAddressBookTest.deadUri.
     */
    private fun deadUri(): String {
        val server = MockWebServer()
        server.start()
        val uri = server.url("/").toString().trimEnd('/')
        server.shutdown()
        return uri
    }

    /**
     * Opens a part URL, on a unique path per call, through the real factory
     * buildDataSourceFactory() returns. The unique path is defensive: two
     * calls sharing one path could, on the streaming-cache-on branch, have
     * the second satisfied from a disk cache entry the first one wrote,
     * which would prove nothing about whether the resolver ran on the second
     * call.
     */
    private fun openThroughTheRealFactory() {
        val dataSpec = DataSpec(
            Uri.parse("${deadUri()}/library/parts/${UUID.randomUUID()}/file.mp3?X-Plex-Token=t")
        )
        val dataSource = factory.buildDataSourceFactory().createDataSource()
        try {
            dataSource.open(dataSpec)
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun theResolverWrapsWhenTheStreamingCacheIsOff() {
        setStreamingCacheSize(0L)

        openThroughTheRealFactory()

        assertEquals(
            "request must have landed on the live server the resolver rewrote it onto, " +
                "not the dead port it was built with",
            1, liveServer.requestCount
        )
    }

    @Test
    fun theResolverWrapsWhenTheStreamingCacheIsOn() {
        setStreamingCacheSize(256L)

        openThroughTheRealFactory()

        assertEquals(
            "request must have landed on the live server the resolver rewrote it onto, " +
                "not the dead port it was built with",
            1, liveServer.requestCount
        )
    }

    companion object {
        private const val DEFAULT_STREAMING_CACHE_SIZE = 256L
    }
}
