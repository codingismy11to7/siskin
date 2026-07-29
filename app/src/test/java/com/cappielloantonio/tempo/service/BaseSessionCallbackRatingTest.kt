package com.cappielloantonio.tempo.service

import android.os.Looper
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.cappielloantonio.tempo.plex.PlexApi
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/**
 * onSetRating against a real socket.
 *
 * Robolectric rather than the plain-JUnit style of BaseSessionCallbackTest:
 * `unitTests.returnDefaultValues = true` makes MediaItem, MediaMetadata and
 * HeartRating answer with stubs, so an assertion about the rating written back
 * into the queue would hold against an implementation that wrote nothing.
 */
@RunWith(RobolectricTestRunner::class)
class BaseSessionCallbackRatingTest {

    private lateinit var server: MockWebServer
    private lateinit var session: MediaSession
    private lateinit var player: Player
    private lateinit var callback: BaseSessionCallback

    private fun trackItem(mediaId: String, hearted: Boolean) = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(MediaMetadata.Builder().setUserRating(HeartRating(hearted)).build())
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        PlexApi().serverUri = server.url("/").toString()

        player = mock()
        session = mock()
        whenever(session.player).thenReturn(player)
        // Nothing is listening for a layout refresh in this test; returning null
        // makes updateMediaNotificationCustomLayout a no-op rather than a mock
        // maze, without skipping the call itself.
        whenever(session.mediaNotificationControllerInfo).thenReturn(null)

        // Two items so "replaced the matching one" is distinguishable from
        // "replaced everything".
        whenever(player.mediaItemCount).thenReturn(2)
        whenever(player.getMediaItemAt(0)).thenReturn(trackItem("other", false))
        whenever(player.getMediaItemAt(1)).thenReturn(trackItem("42", false))

        callback = BaseSessionCallback(RuntimeEnvironment.getApplication(), mock())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * onSetRating resumes on Dispatchers.Main, because it touches the Player.
     * Robolectric runs this test body on that same thread with a paused looper,
     * so blocking straight on get() would deadlock against the very hop being
     * tested -- the looper has to be drained by hand while waiting.
     */
    private fun rate(hearted: Boolean): SessionResult {
        val future = callback.onSetRating(session, mock(), "42", HeartRating(hearted))
        val deadline = System.currentTimeMillis() + 10_000
        while (!future.isDone && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        return future.get(1, TimeUnit.SECONDS)
    }

    @Test
    fun theQueueIsTouchedOnTheApplicationThread() {
        // The regression the coroutine port introduced and this pins: Retrofit's
        // Android platform delivered Call.enqueue callbacks through a main-thread
        // executor, and `suspend` instead resumes wherever the launching scope
        // says. Resuming on IO makes ExoPlayer's verifyApplicationThread throw
        // "Player is accessed on the wrong thread" on every heart tap -- an
        // uncaught crash, seen on a running emulator before this was fixed.
        //
        // The Player here is a mock, so it cannot throw that itself; recording
        // the thread is what makes the requirement testable at all rather than
        // only observable in the car.
        server.enqueue(MockResponse().setResponseCode(200))
        var touchedOn: Thread? = null
        whenever(player.replaceMediaItem(any(), any())).then {
            touchedOn = Thread.currentThread()
            null
        }

        rate(hearted = true)

        assertEquals(Looper.getMainLooper().thread, touchedOn)
    }

    @Test
    fun a200WritesTheRatingBackIntoTheMatchingQueueItemOnly() {
        // `rate` returns Unit now, so "success" is only ever "did not throw".
        // The queue write is what makes the heart survive a track change: the
        // button is rebuilt from player.mediaMetadata.userRating, so an
        // implementation that reports success without replacing the item shows
        // the heart flipping back on the next metadata event.
        server.enqueue(MockResponse().setResponseCode(200))

        val result = rate(hearted = true)

        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)

        val replaced = argumentCaptor<MediaItem>()
        verify(player).replaceMediaItem(eq(1), replaced.capture())
        verify(player, never()).replaceMediaItem(eq(0), any())
        assertEquals("42", replaced.firstValue.mediaId)
        assertEquals(
            HeartRating(true),
            replaced.firstValue.mediaMetadata.userRating
        )
    }

    @Test
    fun clearingTheHeartSendsPlexTheClearValueRatherThanZero() {
        // Verified against a live PMS 1.43.3 server and recorded in
        // SearchClient: rating=0 is a real zero-star rating, and rating=-1 is
        // what clears userRating back to absent.
        server.enqueue(MockResponse().setResponseCode(200))

        rate(hearted = false)

        val query = server.takeRequest().requestUrl!!
        assertEquals("-1", query.queryParameter("rating"))
        assertEquals("42", query.queryParameter("key"))
    }

    /**
     * The HTTP-failure branch of onSetRating cannot work, and this is where that
     * is written down.
     *
     * That branch builds `SessionError(http.code(), http.message())`, but
     * SessionError requires a code that is negative or exactly INFO_CANCELLED --
     * so every status Plex could reject a rating with (401 on a stale token, 404
     * on a bad ratingKey, 500) throws IllegalArgumentException out of the catch
     * that was meant to handle it. The future never completes and the heart
     * button stays on its loading icon.
     *
     * It is a pre-existing defect, unchanged by the coroutine port and
     * deliberately left alone by it. No end-to-end test drives that path: it
     * would have to assert a hang. This pins the mechanism instead, so a fix
     * lands here first -- and so that if media3 ever relaxes the precondition,
     * this fails and sends someone back to onSetRating.
     */
    @Test(expected = IllegalArgumentException::class)
    fun anHttpStatusCannotBeCarriedInASessionErrorAtAll() {
        SessionError(500, "Server Error")
    }

    @Test
    fun anUnreachableServerBecomesErrorUnknown() {
        // Not an HTTP status at all, so there is no code to pass through.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val result = rate(hearted = true)

        assertEquals(SessionError.ERROR_UNKNOWN, result.resultCode)
        verify(player, never()).replaceMediaItem(any(), any())
    }
}
