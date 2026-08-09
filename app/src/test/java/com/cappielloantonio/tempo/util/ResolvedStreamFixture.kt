package com.cappielloantonio.tempo.util

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.SectionKey
import com.cappielloantonio.tempo.plex.api.server.ServerAddressBook
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.UUID

/**
 * A session pointed at a live MockWebServer, and a way to open a part URL aimed
 * somewhere else entirely.
 *
 * This is the shape every "did ServerAddressResolver run" test needs: build a
 * DataSpec against a dead local port, open it through the factory under test,
 * and assert the request landed on the live server the resolver rewrote it onto.
 * A test that skips the resolver leaves the request aimed at the dead port and
 * the live server never sees it.
 *
 * Shared rather than copied because two suites now need it -- the factory that
 * builds it (DynamicMediaSourceFactoryTest) and the gain prefetch that consumes
 * it (ReplayGainPrefetchSourceTest) -- and a resolver regression should not be
 * able to fix itself in one copy of the fixture.
 *
 * Not a test class: it carries no @Test and no runner. Its caller supplies
 * Robolectric, which everything here needs -- DownloadUtil's factories need a
 * real Context, and Preferences and PlexApi read App.getInstance().preferences,
 * which Robolectric caches statically across test methods. That caching is why
 * [setUp] writes the streaming cache size and the session rather than assuming
 * either, and why [tearDown] restores them rather than leaving them for the next
 * suite.
 */
@UnstableApi
class ResolvedStreamFixture {

    lateinit var liveServer: MockWebServer
        private set

    private lateinit var api: PlexApi

    /** Requests the live server has seen. */
    val requestCount: Int get() = liveServer.requestCount

    fun setUp() {
        setStreamingCacheSize(DEFAULT_STREAMING_CACHE_SIZE)

        // DynamicMediaSourceFactory wraps ServerAddressBook.shared in
        // production, so buildDataSourceFactory touches the singleton --
        // resetForTest() is the documented way to keep a failure cooldown from a
        // previous test leaking into this one, or from this one leaking into
        // another suite.
        ServerAddressBook.shared.resetForTest()

        api = PlexApi()
        api.accountToken = "account-token"
        api.serverCandidates = null

        liveServer = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(200).setBody("stream-bytes"))
            start()
        }
        // ServerAddressBook.shared reads PlexApi.session through the same
        // SharedPreferences PlexApi wraps here -- PlexApi holds no state of its
        // own -- so pointing this session at the mock is what the resolver sees.
        api.session = PlexSession(
            accountToken = "account-token",
            serverUri = liveServer.url("/").toString().trimEnd('/'),
            musicSectionKey = SectionKey("5"),
            serverToken = "server-token",
            machineIdentifier = "machine-a"
        )
    }

    fun tearDown() {
        setStreamingCacheSize(DEFAULT_STREAMING_CACHE_SIZE)
        liveServer.shutdown()
        api.session = null
        api.accountToken = null
        api.serverCandidates = null
    }

    fun setStreamingCacheSize(megabytes: Long) {
        App.getInstance().preferences.edit()
            .putString("streaming_cache_size", megabytes.toString())
            .commit()
    }

    /**
     * A real, closed local port: started and immediately shut down, so a
     * connection attempt against it fails fast and deterministically (connection
     * refused) rather than depending on DNS behaviour for a fictitious host,
     * which can be slow or environment-dependent. Same technique as
     * ServerAddressBookTest.deadUri.
     */
    private fun deadUri(): String {
        val server = MockWebServer()
        server.start()
        val uri = server.url("/").toString().trimEnd('/')
        server.shutdown()
        return uri
    }

    /**
     * Opens a part URL, on a unique path per call, through [factory].
     *
     * The unique path is defensive: two calls sharing one path could, on the
     * streaming-cache-on branch, have the second satisfied from a disk cache
     * entry the first one wrote, which would prove nothing about whether the
     * resolver ran on the second call.
     */
    fun openThrough(factory: DataSource.Factory) {
        val dataSpec = DataSpec(
            Uri.parse("${deadUri()}/library/parts/${UUID.randomUUID()}/file.mp3?X-Plex-Token=t")
        )
        val dataSource = factory.createDataSource()
        try {
            dataSource.open(dataSpec)
        } finally {
            dataSource.close()
        }
    }

    companion object {
        const val DEFAULT_STREAMING_CACHE_SIZE = 256L
    }
}
