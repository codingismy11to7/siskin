package com.cappielloantonio.tempo.service

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import com.cappielloantonio.tempo.plex.PlexApi
import com.google.common.util.concurrent.SettableFuture
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Continuous play when the mix response beats the MediaBrowser connection.
 *
 * Robolectric because the whole failure is about *which thread* runs what:
 * PlexMixRepository resumes on Dispatchers.Main, and the MediaBrowser future is
 * completed by that same looper. Only a real looper reproduces that, and only a
 * paused one lets the test put the two events in the order that matters.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class MediaManagerContinuousPlayTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // accountToken has to be set alongside serverUri and musicSectionKey:
        // PlexMixRepository now reads the section key through PlexApi.session,
        // which only reports one once all three are present.
        PlexApi().apply {
            accountToken = "account-token"
            serverUri = server.url("/").toString()
            musicSectionKey = "1"
        }
        // Robolectric keeps App's SharedPreferences in a static field between
        // methods, and isInstantMixUsable() throttles to one mix per 10s -- an
        // earlier method's setLastInstantMix() would otherwise make this one
        // return before doing anything.
        com.cappielloantonio.tempo.App
            .getInstance()
            .preferences
            .edit()
            .putLong("last_instant_mix", 0L)
            .putBoolean("fallback_to_random_tracks", true)
            .commit()
        MediaManager.continuousPlayIsRunning.set(false)
    }

    @After
    fun tearDown() {
        server.shutdown()
        MediaManager.continuousPlayIsRunning.set(false)
    }

    private fun tracksBody(vararg ratingKeys: String) =
        """{"MediaContainer":{"Metadata":[${
            ratingKeys.joinToString(",") { """{"ratingKey":"$it","type":"track","title":"Track $it"}""" }
        }]}}"""

    private fun playedItem() = MediaItem.Builder().setMediaId("seed").build()

    private fun browserWithQueue(vararg mediaIds: String): MediaBrowser {
        val browser = mock<MediaBrowser>()
        whenever(browser.mediaItemCount).thenReturn(mediaIds.size)
        whenever(browser.currentMediaItem).thenReturn(null)
        whenever(browser.nextMediaItemIndex).thenReturn(-1)
        mediaIds.forEachIndexed { i, id ->
            whenever(browser.getMediaItemAt(i)).thenReturn(MediaItem.Builder().setMediaId(id).build())
        }
        return browser
    }

    private fun mainLooper() = shadowOf(Looper.getMainLooper())

    /** Runs the queued mix launch so the HTTP request actually goes out. */
    private fun pumpUntilResponseIsQueued() {
        mainLooper().idle()
        assertTrue(
            "the mix request never reached the server",
            server.takeRequest(10, TimeUnit.SECONDS) != null,
        )
        // The resumption is posted by an OkHttp thread once the body is read;
        // wait for it to land so the browser completion below is queued behind
        // it, which is the ordering the deadlock needs.
        val deadline = System.currentTimeMillis() + 10_000
        while (mainLooper().isIdle && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertFalse("the mix response was never dispatched to the main looper", mainLooper().isIdle)
    }

    /**
     * The application-thread deadlock this restructuring exists to remove.
     *
     * `dedupAgainstQueue` used to call `existingBrowserFuture.get()`. It runs
     * inside PlexMixRepository's callback, which resumes on Dispatchers.Main,
     * and the future it awaited is the one `MediaBrowser.buildAsync()` returns
     * -- completed by that same looper. So whenever the mix response arrived
     * before the browser finished connecting, the only thread that could
     * complete the future was parked waiting for it. Not jank: an ANR.
     *
     * The test stages exactly that order -- mix resumption queued first,
     * browser completion queued behind it -- and then runs the looper. Against
     * the blocking version `idle()` never returns, so the watchdog below is what
     * keeps that a failure rather than a hung suite: it completes the future
     * from another thread to release the deadlock and records that it had to.
     */
    @Test
    fun aMixResponseArrivingBeforeTheBrowserConnectsDoesNotBlockTheApplicationThread() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksBody("99")))
        val browser = browserWithQueue()
        val future = SettableFuture.create<MediaBrowser>()
        val finished = CountDownLatch(1)
        val rescued = AtomicBoolean(false)

        val watchdog =
            Thread {
                if (!finished.await(5, TimeUnit.SECONDS)) {
                    rescued.set(true)
                    future.set(browser)
                }
            }
        watchdog.start()

        MediaManager.continuousPlay(playedItem(), future) { finished.countDown() }
        pumpUntilResponseIsQueued()
        Handler(Looper.getMainLooper()).post { future.set(browser) }

        mainLooper().idle()
        watchdog.join(10_000)

        assertFalse(
            "dedupAgainstQueue blocked the application thread on a future only that thread could complete",
            rescued.get(),
        )
        assertEquals(0, finished.count)
        assertFalse("continuousPlayIsRunning was left set", MediaManager.continuousPlayIsRunning.get())
        assertEquals(listOf("99"), enqueuedIds(browser))
    }

    /** The media ids handed to the browser, in order, across every addMediaItems call. */
    private fun enqueuedIds(browser: MediaBrowser): List<String> {
        val captor = argumentCaptor<List<MediaItem>>()
        verify(browser, atLeastOnce()).addMediaItems(captor.capture())
        return captor.allValues.flatten().map { it.mediaId }
    }

    /**
     * Behaviour the restructuring had to preserve: candidates already in the
     * queue are dropped, and a similar tier that leaves nothing new falls
     * through to the random tier rather than enqueueing duplicates.
     */
    @Test
    fun similarTracksAlreadyInTheQueueFallThroughToRandom() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksBody("11")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksBody("22")))
        val browser = browserWithQueue("11")
        val future = SettableFuture.create<MediaBrowser>()
        future.set(browser)
        val finished = CountDownLatch(1)

        MediaManager.continuousPlay(playedItem(), future) { finished.countDown() }
        drainUntil(finished)

        assertEquals(0, finished.count)
        // "11" was already queued, so only the random tier's "22" is added.
        assertEquals(listOf("22"), enqueuedIds(browser))
        assertFalse(MediaManager.continuousPlayIsRunning.get())
    }

    /**
     * The flag is cleared nowhere but the completion path, so a tier that fails
     * outright must still reach it -- otherwise Instant Mix is dead for the rest
     * of the process, with no exception and no way back short of a restart.
     */
    @Test
    fun bothTiersFailingStillClearsTheRunningFlag() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        val future = SettableFuture.create<MediaBrowser>()
        future.set(browserWithQueue())
        val finished = CountDownLatch(1)

        MediaManager.continuousPlay(playedItem(), future) { finished.countDown() }
        drainUntil(finished)

        assertEquals(0, finished.count)
        assertFalse(MediaManager.continuousPlayIsRunning.get())
    }

    /** A null browser future must still complete, enqueueing nothing to dedup against. */
    @Test
    fun aNullBrowserFutureStillCompletes() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(tracksBody("99")))
        val finished = CountDownLatch(1)

        MediaManager.continuousPlay(playedItem(), null) { finished.countDown() }
        drainUntil(finished)

        assertEquals(0, finished.count)
        assertFalse(MediaManager.continuousPlayIsRunning.get())
    }

    /**
     * Bounded, so a path that never calls back fails here instead of hanging the
     * suite. The looper is paused under Robolectric and delivery happens on it,
     * so it has to be driven by hand rather than simply awaited.
     */
    private fun drainUntil(latch: CountDownLatch) {
        val deadline = System.currentTimeMillis() + 15_000
        while (latch.count > 0L && System.currentTimeMillis() < deadline) {
            mainLooper().idle()
            Thread.sleep(5)
        }
        assertEquals("continuous play never completed", 0, latch.count)
    }
}
