package com.cappielloantonio.tempo.service

import android.accounts.AccountManager
import android.content.Context
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.SectionKey
import com.cappielloantonio.tempo.plex.api.search.TimelineState
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/** Robolectric because PlexApi reads the app's preferences and the system account. */
@RunWith(RobolectricTestRunner::class)
class PlexScrobblerTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        shadowOf(AccountManager.get(context))
            .addAuthenticator(context.getString(R.string.plex_account_type))
        server = MockWebServer()
        server.start()
        signInTo(server, "acct")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun signInTo(
        server: MockWebServer,
        accountToken: String,
    ) {
        PlexApi().session =
            PlexSession(
                accountToken = accountToken,
                serverUri = server.url("/").toString(),
                musicSectionKey = SectionKey("4"),
                serverToken = null,
                machineIdentifier = "machine-${server.port}",
                owned = true,
            )
    }

    private fun report(
        ratingKey: String,
        state: TimelineState,
    ) = PlexScrobbler.report(ratingKey, "/library/parts/1", state, 0L, null)

    private fun nextState(server: MockWebServer = this.server) =
        server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("state")

    /** Sent together, a pause could land before the heartbeat ahead of it and leave the car "playing". */
    @Test
    fun aReportWaitsForTheOneBeforeIt() {
        server.enqueue(MockResponse().setResponseCode(200).setHeadersDelay(1, TimeUnit.SECONDS))
        server.enqueue(MockResponse().setResponseCode(200))

        report("42", TimelineState.PLAYING)
        report("42", TimelineState.PAUSED)

        assertEquals("playing", nextState())
        assertNull(server.takeRequest(500, TimeUnit.MILLISECONDS))
        assertEquals("paused", nextState())
    }

    /** Sent as the new member, it would open a Now Playing entry that nothing closes. */
    @Test
    fun aHeartbeatQueuedBeforeASessionChangeIsDropped() {
        MockWebServer().use { other ->
            server.enqueue(MockResponse().setResponseCode(200).setHeadersDelay(1, TimeUnit.SECONDS))
            server.enqueue(MockResponse().setResponseCode(200))
            other.enqueue(MockResponse().setResponseCode(200))
            report("42", TimelineState.PAUSED)
            assertEquals("paused", nextState())
            report("42", TimelineState.PLAYING)
            report("42", TimelineState.STOPPED)
            signInTo(other, "other-acct")

            assertEquals("stopped", nextState())
            assertNull(other.takeRequest(500, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun aStoppedReportKeepsTheServerCurrentWhenItWasMade() {
        MockWebServer().use { other ->
            server.enqueue(MockResponse().setResponseCode(200).setHeadersDelay(1, TimeUnit.SECONDS))
            server.enqueue(MockResponse().setResponseCode(200))
            report("42", TimelineState.PAUSED)
            assertEquals("paused", nextState())
            report("42", TimelineState.STOPPED)
            signInTo(other, "other-acct")

            assertEquals("stopped", nextState())
        }
    }

    @Test
    fun aServerChangeIsFollowed() {
        server.enqueue(MockResponse().setResponseCode(200))
        report("42", TimelineState.PLAYING)
        assertEquals("playing", nextState())

        MockWebServer().use { other ->
            other.enqueue(MockResponse().setResponseCode(200))
            signInTo(other, "other-acct")

            report("42", TimelineState.PAUSED)

            assertEquals("paused", nextState(other))
        }
    }
}
