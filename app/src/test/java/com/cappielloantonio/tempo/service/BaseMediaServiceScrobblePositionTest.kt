package com.cappielloantonio.tempo.service

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.util.Constants
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * Pins the trickiest of MediaManager.scrobble's four call sites: the
 * auto-transition branch of onPositionDiscontinuity in
 * initializePlayerListener.
 *
 * Robolectric rather than plain JUnit for the same reason as
 * MediaManagerScrobbleTest: a real MediaMetadata/Bundle is needed so the
 * scrobble call is not silently skipped by the partKey-null guard.
 */
@RunWith(RobolectricTestRunner::class)
class BaseMediaServiceScrobblePositionTest {
    private lateinit var server: MockWebServer
    private lateinit var service: BaseMediaService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        PlexApi().serverUri = server.url("/").toString()
        service = BaseMediaService()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun trackItem(ratingKey: String) =
        MediaItem
            .Builder()
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setExtras(
                        Bundle().apply {
                            putString(PlexMediaMapper.EXTRA_ID, ratingKey)
                            putString(PlexMediaMapper.EXTRA_PART_KEY, "/library/parts/1")
                            putString(PlexMediaMapper.EXTRA_TYPE, Constants.MEDIA_TYPE_MUSIC)
                        },
                    ).build(),
            ).build()

    private fun positionInfo(
        mediaItem: MediaItem?,
        positionMs: Long,
    ) = Player.PositionInfo(
        // windowUid =
        null,
        // mediaItemIndex =
        0,
        // mediaItem =
        mediaItem,
        // periodUid =
        null,
        // periodIndex =
        0,
        // positionMs =
        positionMs,
        // contentPositionMs =
        positionMs,
        // adGroupIndex =
        C.INDEX_UNSET,
        // adIndexInAdGroup =
        C.INDEX_UNSET,
    )

    /**
     * Catches a regression to `player.currentPosition` at this call site.
     * By the time an auto-transition fires, the player has already moved to
     * the new item, so `player.currentPosition` reports the *new* track's
     * position -- here rigged to a deliberately distinct value -- while the
     * scrobble is reporting against the *old* track's ratingKey. Only
     * `oldPosition.positionMs` is the position at which the old track was
     * actually left, which is what a "stopped" report must carry.
     */
    @Test
    fun autoTransitionScrobblesTheOldTracksPositionNotThePlayersCurrentPosition() {
        server.enqueue(MockResponse().setResponseCode(200))

        val player = mock<Player>()
        whenever(player.currentPosition).thenReturn(999_000L)

        service.initializePlayerListener(player)
        val listenerCaptor = argumentCaptor<Player.Listener>()
        verify(player).addListener(listenerCaptor.capture())

        val oldPosition = positionInfo(trackItem("42"), positionMs = 123_456L)
        val newPosition = positionInfo(trackItem("43"), positionMs = 0L)

        listenerCaptor.firstValue.onPositionDiscontinuity(
            oldPosition,
            newPosition,
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        )

        val request =
            server.takeRequest(5, TimeUnit.SECONDS)
                ?: throw AssertionError("PlexScrobbler never sent a request")
        assertEquals("123456", request.requestUrl!!.queryParameter("time"))
        assertEquals("42", request.requestUrl!!.queryParameter("ratingKey"))
        assertEquals("stopped", request.requestUrl!!.queryParameter("state"))
    }
}
