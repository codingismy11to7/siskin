package com.cappielloantonio.tempo.plex.api.auth

import com.cappielloantonio.tempo.plex.api.annotatedEndpoints
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * What AuthService actually puts on the wire.
 *
 * These paths are string literals in annotations, and a typo in one fails
 * *open*: Plex answers 200 to a request whose parameters it does not
 * recognise, the app narrows the unexpected body to an empty list, and the
 * browse layer reads that as an empty library. Nothing else in the suite
 * would notice.
 *
 * A bare Retrofit rather than PlexRetrofitFactory, because plexTv() hardcodes
 * https://plex.tv/api/v2/ and so cannot be pointed at a mock server at all.
 * That base URL is asserted in PlexRetrofitFactoryTest instead.
 */
class AuthServiceTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun service(): AuthService = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(AuthService::class.java)

    @Test
    fun createPinPostsToPins() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        service().createPin()

        val request = server.takeRequest()
        // The one POST in the layer; a GET here would read an existing pin
        // rather than mint one.
        assertEquals("POST", request.method)
        assertEquals("/pins", request.requestUrl?.encodedPath)
    }

    @Test
    fun getPinReadsOnePinById() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        service().getPin(42L)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/pins/42", request.requestUrl?.encodedPath)
    }

    @Test
    fun getResourcesAsksForHttpsAndRelayConnections() = runTest {
        // Both default to 1 and no caller passes them, so this is the only
        // thing that would notice a flipped default. Without includeHttps the
        // account's servers come back advertising http-only connections, which
        // ServerProbe would then be racing for nothing.
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        service().getResources()

        val request = server.takeRequest()
        assertEquals("/resources", request.requestUrl?.encodedPath)
        assertEquals("1", request.requestUrl?.queryParameter("includeHttps"))
        assertEquals("1", request.requestUrl?.queryParameter("includeRelay"))
    }

    @Test
    fun everyEndpointIsCovered() {
        // Fails when an endpoint is added to AuthService without a test above.
        // The gap this file closes formed exactly that way.
        assertEquals(
            setOf("createPin", "getPin", "getResources"),
            annotatedEndpoints(AuthService::class.java)
        )
    }
}
