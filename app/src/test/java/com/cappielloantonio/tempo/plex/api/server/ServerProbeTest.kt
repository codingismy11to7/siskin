package com.cappielloantonio.tempo.plex.api.server

import com.cappielloantonio.tempo.plex.models.Connection
import com.cappielloantonio.tempo.plex.models.Resource
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe exists because a connection Plex advertises may be unreachable from
 * wherever the car is, so these tests use real sockets: a MockWebServer that
 * answers, one that accepts and then hangs, and a port with nothing on it.
 */
class ServerProbeTest {
    private fun connection(
        uri: String,
        local: Boolean = true,
        relay: Boolean = false,
    ) = Connection().apply {
        this.uri = uri
        this.local = local
        this.relay = relay
    }

    private fun resource(vararg connections: Connection) =
        Resource().apply {
            this.connections = connections.toList()
        }

    private fun identityServer() =
        MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(200).setBody("""{"MediaContainer":{}}"""))
            start()
        }

    /** Accepts the TCP connection and never answers -- the Docker gateway case. */
    private fun hangingServer() =
        MockWebServer().apply {
            enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            start()
        }

    /** A port with nothing listening: connection refused. */
    private fun deadUri(): String {
        val server = MockWebServer()
        server.start()
        val uri = server.url("/").toString().trimEnd('/')
        server.shutdown()
        return uri
    }

    @Test
    fun adoptsTheFirstConnectionThatAnswers() =
        runTest {
            val live = identityServer()
            val hanging = hangingServer()
            val liveUri = live.url("/").toString().trimEnd('/')

            // The bug this fixes: the unreachable connection is listed first, exactly
            // as plex.tv orders a containerised server's Docker bridge gateway ahead
            // of its real LAN address.
            val elapsed = System.currentTimeMillis()
            val winner =
                ServerProbe().bestConnectionUri(
                    resource(
                        connection(hanging.url("/").toString().trimEnd('/')),
                        connection(liveUri),
                    ),
                )
            val took = System.currentTimeMillis() - elapsed

            assertEquals(liveUri, winner)
            // The whole point: a dead candidate ahead of a live one must not be
            // waited out. The hanging server would hold the read timeout open.
            assertTrue("took ${took}ms, should not have waited for the hang", took < 2_000)

            live.shutdown()
            hanging.shutdown()
        }

    @Test
    fun fallsBackToRelayWhenEveryDirectConnectionFails() =
        runTest {
            val relay = identityServer()
            val relayUri = relay.url("/").toString().trimEnd('/')

            val winner =
                ServerProbe().bestConnectionUri(
                    resource(
                        connection(deadUri()),
                        connection(deadUri()),
                        connection(relayUri, local = false, relay = true),
                    ),
                )

            assertEquals(relayUri, winner)
            relay.shutdown()
        }

    @Test
    fun prefersADirectConnectionOverAWorkingRelay() =
        runTest {
            val direct = identityServer()
            val relay = identityServer()
            val directUri = direct.url("/").toString().trimEnd('/')

            // Both answer. Relay is bandwidth-limited by Plex, so winning a race is
            // not the same as being the right answer -- it is a fallback tier, not a
            // competitor.
            val winner =
                ServerProbe().bestConnectionUri(
                    resource(
                        connection(relay.url("/").toString().trimEnd('/'), local = false, relay = true),
                        connection(directUri),
                    ),
                )

            assertEquals(directUri, winner)
            direct.shutdown()
            relay.shutdown()
        }

    @Test
    fun returnsNullWhenNothingAnswers() =
        runTest {
            val winner = ServerProbe().bestConnectionUri(resource(connection(deadUri())))
            assertNull(winner)
        }

    @Test
    fun returnsNullForAResourceWithNoConnections() =
        runTest {
            assertNull(ServerProbe().bestConnectionUri(Resource()))
            assertNull(ServerProbe().bestConnectionUri(resource()))
        }

    @Test
    fun splitsRelayOutOfTheDirectTier() {
        val candidates =
            ServerProbe.candidates(
                resource(
                    connection("https://lan", local = true, relay = false),
                    connection("https://relay", local = false, relay = true),
                    connection("https://public", local = false, relay = false),
                ),
            )

        assertEquals(listOf("https://lan", "https://public"), candidates.direct)
        assertEquals(listOf("https://relay"), candidates.relay)
    }

    @Test
    fun ignoresConnectionsWithNoUri() {
        val candidates =
            ServerProbe.candidates(
                resource(
                    connection("", local = true, relay = false),
                    connection("   ", local = true, relay = false),
                    connection("https://lan", local = true, relay = false),
                ),
            )

        assertEquals(listOf("https://lan"), candidates.direct)
    }

    @Test
    fun hasUsableConnectionIgnoresBlanks() {
        assertTrue(ServerProbe.hasUsableConnection(resource(connection("https://lan"))))
        assertTrue(!ServerProbe.hasUsableConnection(resource(connection(""))))
        assertTrue(!ServerProbe.hasUsableConnection(Resource()))
    }

    @Test
    fun hidesAServerReachableOnlyOverCleartext() {
        // Secure connections disabled on the server, or a custom http access URL.
        // The app permits no cleartext traffic, so this could never be opened --
        // offering it would only defer the failure until after it was picked.
        assertTrue(
            !ServerProbe.hasUsableConnection(resource(connection("http://192.168.1.5:32400"))),
        )
    }

    @Test
    fun keepsAServerThatOffersTlsAlongsideCleartext() {
        // The ordinary case for a LAN server: Plex issues a real certificate for
        // the plex.direct name covering the same private address.
        assertTrue(
            ServerProbe.hasUsableConnection(
                resource(
                    connection("http://192.168.1.5:32400"),
                    connection("https://192-168-1-5.abc.plex.direct:32400"),
                ),
            ),
        )
    }
}
