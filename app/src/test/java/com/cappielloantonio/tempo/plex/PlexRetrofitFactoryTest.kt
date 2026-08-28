package com.cappielloantonio.tempo.plex

import com.cappielloantonio.tempo.car.VehicleIdentity
import com.cappielloantonio.tempo.car.VehicleInfoReader
import com.cappielloantonio.tempo.car.VehicleInfoSource
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

    /**
     * A car that names itself `Škoda` must reach Plex spelled that way.
     *
     * `Request.Builder.header` rejects any value outside 0x20-0x7E by throwing,
     * and on this path the throw lands uncaught on an OkHttp dispatcher thread
     * -- which on Android takes the process down. These headers ride on the
     * first POST /pins, so the car could not sign in at all. Plex asks for
     * UTF-8 here rather than for a transliteration, so this asserts the exact
     * bytes come back off a real socket. See the 2026-08-27 design.
     */
    @Test
    fun aNonAsciiVehicleNameSurvivesTheRoundTrip() {
        withVehicle(VehicleIdentity("Škoda", "Octavia", 2024, VehicleInfoSource.VEHICLE)) {
            val api = PlexApi()
            val client = clientOf(PlexRetrofitFactory.server(api, api.serverUri, api.serverToken))

            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            client.newCall(Request.Builder().url(server.url("/identity")).build()).execute().close()

            val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("Škoda", recorded.getHeader("X-Plex-Device"))
            assertEquals("Octavia", recorded.getHeader("X-Plex-Model"))
            assertEquals("2024 Škoda Octavia", recorded.getHeader("X-Plex-Device-Name"))
        }
    }

    /**
     * The trap in sending the identity headers as a `Headers` set: handing one
     * to `Request.Builder.headers` *replaces* everything the request already
     * carried, and by the time the interceptor runs Retrofit has put
     * Content-Type and friends there.
     */
    @Test
    fun theIdentityHeadersMergeRatherThanReplace() {
        val api = PlexApi()
        val client = clientOf(PlexRetrofitFactory.server(api, api.serverUri, api.serverToken))

        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        client
            .newCall(
                Request
                    .Builder()
                    .url(server.url("/identity"))
                    .header("X-Set-By-Retrofit", "kept")
                    .build(),
            ).execute()
            .close()

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("kept", recorded.getHeader("X-Set-By-Retrofit"))
        assertEquals("Siskin", recorded.getHeader("X-Plex-Product"))
    }

    /**
     * The reader caches process-wide and takes no injected identity, because
     * android.car cannot be on the unit-test classpath -- so reflection is the
     * only seam. Restores what it found, since the cache outlives the method.
     */
    private fun withVehicle(
        identity: VehicleIdentity,
        body: () -> Unit,
    ) {
        val field = VehicleInfoReader::class.java.getDeclaredField("resolved").apply { isAccessible = true }
        val previous = field.get(VehicleInfoReader)
        field.set(VehicleInfoReader, identity)
        try {
            body()
        } finally {
            field.set(VehicleInfoReader, previous)
        }
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

    @Test
    fun plexTvIsPinnedToTheV2Api() {
        // A fixed string literal with the same failure mode as a path: wrong,
        // and every signed-out call goes somewhere that is not Plex. Nothing
        // else asserts it, and it cannot be reached through a mock server --
        // this method hardcodes the URL, which is why the AuthService tests
        // build their own Retrofit.
        assertEquals(
            "https://plex.tv/api/v2/",
            PlexRetrofitFactory.plexTv(PlexApi()).baseUrl().toString(),
        )
    }

    @Test
    fun aServerUriGainsTheTrailingSlashRetrofitDemands() {
        // Retrofit throws at construction without it, and Plex advertises
        // connection URIs with no trailing slash -- so this normalization is
        // on the path of every server the probe picks.
        assertEquals(
            "https://10.0.0.5:32400/",
            PlexRetrofitFactory
                .server(PlexApi(), "https://10.0.0.5:32400", null)
                .baseUrl()
                .toString(),
        )
    }

    /**
     * The fallback is what keeps a fresh install from crashing on its first
     * browse.
     *
     * `Retrofit.Builder().baseUrl()` throws on an address it cannot parse, and it
     * throws at *construction* -- so it lands outside `plexCall`, which catches
     * IOException and HttpException around the *call* and would never see it.
     *
     * That is not an edge case. `PlexBrowseRepository.refreshClients` builds its
     * clients from `session?.serverUri`, which is null until a server is chosen,
     * and MediaService creates that repository the first time the car browses.
     * Handing back an unreachable-but-parseable address turns "no server yet"
     * into an ordinary connection failure, which becomes
     * `PlexTransportFailure.Unreachable(Server)` and reaches the user as the
     * sign-in affordance instead of a stack trace.
     */
    @Test
    fun anUnusableServerUriFallsBackInsteadOfThrowing() {
        val cases =
            listOf(
                null, // signed out -- session?.serverUri on a fresh install
                "",
                "   ",
                "not a url",
                "https://", // parses far enough to have no host
            )

        cases.forEach { uri ->
            assertEquals(
                "expected the placeholder for ${uri?.let { "\"$it\"" }}",
                "https://localhost/",
                PlexRetrofitFactory.server(PlexApi(), uri, null).baseUrl().toString(),
            )
        }
    }
}
