package com.cappielloantonio.tempo.service

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * `MediaManager.scrobble` against a real socket.
 *
 * Robolectric rather than plain JUnit: `unitTests.returnDefaultValues = true`
 * makes a mocked/stub MediaItem's `mediaMetadata.extras` answer null, which
 * would make every call here silently no-op through the `partKey == null`
 * guard and pass whether `positionMs` is plumbed through correctly or not.
 * Building a real MediaItem with real extras needs Robolectric's shadow
 * Bundle/MediaMetadata.
 */
@RunWith(RobolectricTestRunner::class)
class MediaManagerScrobbleTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        PlexApi().serverUri = server.url("/").toString()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun trackItem(ratingKey: String, partKey: String) = MediaItem.Builder()
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setExtras(Bundle().apply {
                    putString(PlexMediaMapper.EXTRA_ID, ratingKey)
                    putString(PlexMediaMapper.EXTRA_PART_KEY, partKey)
                })
                .build()
        )
        .build()

    /**
     * Catches a regression back to the pre-fix behaviour, where `scrobble`
     * took no position parameter and always reported `timeMs = 0L` to Plex's
     * `/:/timeline` endpoint regardless of how much of the track had actually
     * played. Plex decides whether a play "counts" from that value, so a
     * hardcoded 0 silently drops every play. Deliberately re-hardcoding the
     * `0L` inside `scrobble` (while keeping the 3-arg signature) makes this
     * assertion fail: the request would carry `time=0` instead of the real
     * position passed in below.
     */
    @Test
    fun scrobbleSendsTheGivenPositionRatherThanZero() {
        server.enqueue(MockResponse().setResponseCode(200))

        MediaManager.scrobble(trackItem("42", "/library/parts/99"), true, 123_456L)

        val request = server.takeRequest(5, TimeUnit.SECONDS)
            ?: throw AssertionError("PlexScrobbler never sent a request")
        assertEquals("123456", request.requestUrl!!.queryParameter("time"))
        assertEquals("42", request.requestUrl!!.queryParameter("ratingKey"))
    }

    @Test
    fun scrobbleSendsAZeroPositionAtTheStartOfPlayback() {
        // Not every call site is wrong to pass 0 -- a track that has genuinely
        // just started reports 0 legitimately. This pins that 0 is still a
        // valid value to send, not something scrobble should reject or alter.
        server.enqueue(MockResponse().setResponseCode(200))

        MediaManager.scrobble(trackItem("42", "/library/parts/99"), false, 0L)

        val request = server.takeRequest(5, TimeUnit.SECONDS)
            ?: throw AssertionError("PlexScrobbler never sent a request")
        assertEquals("0", request.requestUrl!!.queryParameter("time"))
        assertEquals("playing", request.requestUrl!!.queryParameter("state"))
    }
}
