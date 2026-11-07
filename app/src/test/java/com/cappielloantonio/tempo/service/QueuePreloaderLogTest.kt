package com.cappielloantonio.tempo.service

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.api.media.MediaUrlBuilder
import com.cappielloantonio.tempo.util.LoggedTokenCheck.assertTokenNotLogged
import com.cappielloantonio.tempo.util.PlexAccountFixture
import com.cappielloantonio.tempo.util.Preferences
import com.cappielloantonio.tempo.util.QueuePlayerFixture.playerOver
import com.cappielloantonio.tempo.util.StreamingCacheTestHooks
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLog
import java.util.UUID

/** The stream URLs QueuePreloader fetches carry the server token; its log lines must not. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class QueuePreloaderLogTest {
    private val server = MockWebServer()
    private val api = PlexApi()

    @Before
    fun setUp() {
        server.start()
        Preferences.setPrecacheTracksCount(2)
        Preferences.setStreamingCacheSizeOverrideMegabytes(16)
        // Pins the cache key to its machineIdentifier branch; left unset, the
        // branch would depend on what an earlier suite left behind.
        api.accountToken = "account-token"
        api.session = PlexAccountFixture.aSession(serverUri = server.url("/").toString(), serverToken = TOKEN)
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        QueuePreloader.cancel()
        QueuePreloader.awaitIdle()
        StreamingCacheTestHooks.release()
        api.session = null
        api.accountToken = null
        Preferences.setStreamingCacheSizeOverrideMegabytes(null)
        Preferences.setPrecacheTracksCount(Preferences.DEFAULT_PRECACHE_TRACKS_COUNT)
        server.shutdown()
    }

    @Test
    fun `neither a cached track nor a failed one logs the token`() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(1024))))
        server.enqueue(MockResponse().setResponseCode(404))

        // Fresh part keys each run: the streaming cache outlives a test method,
        // and a track it already holds is skipped without logging anything.
        val run = UUID.randomUUID()
        val items =
            listOf("current", "next", "after").map { name ->
                MediaItem.fromUri(MediaUrlBuilder.streamUrl(server.url("/").toString(), "/library/parts/$run/$name.mp3", TOKEN)!!)
            }

        QueuePreloader.preload(RuntimeEnvironment.getApplication(), playerOver(items, currentIndex = 0))
        QueuePreloader.awaitIdle()

        assertEquals(2, server.requestCount)
        val lines = ShadowLog.getLogsForTag("QueuePreloader").map { it.msg }
        assertEquals(1, lines.count { it.startsWith("Pre-cached ") })
        assertEquals(1, lines.count { it.startsWith("Pre-cache aborted ") })
        assertTokenNotLogged(TOKEN)
    }

    private companion object {
        const val TOKEN = "secret-token"
    }
}
