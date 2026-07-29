package com.cappielloantonio.tempo.plex

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * Robolectric because PlexApi reads App.getInstance().preferences, which needs a
 * live Context.
 */
@RunWith(RobolectricTestRunner::class)
class PlexRetrofitFactoryTest {

    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
        PlexApi().apply {
            serverUri = server.url("/").toString()
            accountToken = "account-token"
            serverToken = "server-token"
        }
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun clientOf(retrofit: retrofit2.Retrofit) = retrofit.callFactory() as OkHttpClient

    /**
     * Catches a regression to building a whole `OkHttpClient.Builder()` per
     * call, which is what this did before.
     *
     * An OkHttpClient owns its connection pool and its dispatcher's thread pool,
     * so a fresh one reuses nothing: every request opens a new socket and pays a
     * new TLS handshake. The callers are all per-use -- PlexScrobbler builds one
     * per scrobble and scrobbles fire on every play *and* pause,
     * BaseSessionCallback builds one per heart tap, PlexMixRepository one per
     * mix -- so on a head unit that is several handshakes a track. Asserting on
     * the pool and dispatcher identity is what makes "shares connections" a fact
     * rather than an intention.
     */
    @Test
    fun everyClientSharesOneConnectionPoolAndOneDispatcher() {
        val api = PlexApi()
        val first = clientOf(PlexRetrofitFactory.server(api, api.serverUri, api.serverToken))
        val second = clientOf(PlexRetrofitFactory.server(api, api.serverUri, api.serverToken))
        val plexTv = clientOf(PlexRetrofitFactory.plexTv(PlexApi()))

        assertSame(first.connectionPool, second.connectionPool)
        assertSame(first.connectionPool, plexTv.connectionPool)
        assertSame(first.dispatcher, second.dispatcher)
        assertSame(first.dispatcher, plexTv.dispatcher)
    }

    /**
     * The constraint the sharing must not break: the identity interceptor closes
     * over one PlexApi's header supplier, so it has to stay per-client. Sharing
     * the *client* itself -- one singleton with one interceptor -- would send
     * whichever token was installed first to both hosts: plex.tv would get the
     * server token, or the media server would get the account token, which a
     * shared server rejects outright.
     */
    @Test
    fun eachClientKeepsItsOwnIdentityInterceptor() {
        val api = PlexApi()
        val plexTvClient = clientOf(PlexRetrofitFactory.plexTv(api))
        val serverClient = clientOf(PlexRetrofitFactory.server(api, api.serverUri, api.serverToken))

        assertNotSame(plexTvClient, serverClient)

        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        plexTvClient.newCall(Request.Builder().url(server.url("/probe")).build()).execute().close()
        assertEquals("account-token", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("X-Plex-Token"))

        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        serverClient.newCall(Request.Builder().url(server.url("/probe")).build()).execute().close()
        assertEquals("server-token", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("X-Plex-Token"))
    }

    @Test
    fun theSharedTimeoutsSurviveOnEachDerivedClient() {
        // newBuilder() copies them, but nothing else in the codebase would fail
        // if a future edit set them only on the derived builder for one caller.
        val api = PlexApi()
        val client = clientOf(PlexRetrofitFactory.server(api, api.serverUri, api.serverToken))

        assertEquals(TimeUnit.MINUTES.toMillis(1).toInt(), client.callTimeoutMillis)
        assertEquals(TimeUnit.SECONDS.toMillis(20).toInt(), client.connectTimeoutMillis)
        assertEquals(TimeUnit.SECONDS.toMillis(30).toInt(), client.readTimeoutMillis)
    }
}
