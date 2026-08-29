package com.cappielloantonio.tempo.repository

import com.cappielloantonio.tempo.App
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a Playlist Mix actually asks the server for.
 *
 * The bug these pin: a Mix of a list over the limit used to fetch its first N
 * in playlist order and shuffle those, which on an artist-ordered playlist is
 * one letter of the alphabet. See the 2026-08-28 mix paging design.
 */
@RunWith(RobolectricTestRunner::class)
class PlexBrowseMixSourceTest {
    private val fixture = PlexBrowseTestServer()
    private val server: MockWebServer get() = fixture.server

    @Before
    fun setUp() {
        fixture.start()
        App
            .getInstance()
            .preferences
            .edit()
            .remove("mix_track_limit")
            .commit()
    }

    @After
    fun tearDown() = fixture.stop()

    /**
     * A playlist metadata probe body -- what `GET playlists/{id}` answers.
     *
     * Built by string concatenation rather than a raw literal with a
     * conditional hole in it: the content value is itself a URL full of
     * percent-escapes, and interpolating one into a JSON literal inside a
     * Kotlin template is a quoting problem nobody should have to read.
     */
    private fun probe(
        leafCount: Int,
        smart: Boolean,
        content: String? = null,
    ): String {
        val contentField = content?.let { ""","content":"$it"""" }.orEmpty()
        return """{"MediaContainer":{"size":1,"Metadata":[""" +
            """{"ratingKey":"9","type":"playlist","title":"P",""" +
            """"leafCount":$leafCount,"smart":$smart$contentField}]}}"""
    }

    private val emptyTracks = """{"MediaContainer":{"size":0,"totalSize":0}}"""

    private fun routeBy(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = handler(request)
            }
    }

    @Test
    fun `a playlist under the limit is fetched whole and unsorted`() =
        runTest {
            val paths = mutableListOf<String>()
            routeBy { request ->
                paths += request.requestUrl?.encodedPath.orEmpty()
                val body =
                    if (request.requestUrl?.encodedPath == "/playlists/9") {
                        probe(leafCount = 120, smart = true, content = null)
                    } else {
                        emptyTracks
                    }
                MockResponse().setResponseCode(200).setBody(body)
            }

            PlexBrowseRepository().getPlaylistTracksForShuffle("9").get()

            assertEquals(listOf("/playlists/9", "/playlists/9/items"), paths)
        }

    @Test
    fun `a smart playlist over the limit is sampled through its own query`() =
        runTest {
            val urls = mutableListOf<String>()
            var sampledSize: String? = null
            routeBy { request ->
                urls += request.path.orEmpty()
                val body =
                    if (request.requestUrl?.encodedPath == "/playlists/9") {
                        probe(
                            leafCount = 12596,
                            smart = true,
                            content = "library://x/directory/%2Flibrary%2Fsections%2F7%2Fall%3Ftype%3D10",
                        )
                    } else {
                        sampledSize = request.getHeader("X-Plex-Container-Size")
                        emptyTracks
                    }
                MockResponse().setResponseCode(200).setBody(body)
            }

            PlexBrowseRepository().getPlaylistTracksForShuffle("9").get()

            // Never /playlists/9/items -- that endpoint cannot randomise, which
            // is the whole reason this branch exists.
            assertTrue(urls.none { it.startsWith("/playlists/9/items") })
            assertTrue(urls.any { it.startsWith("/library/sections/7/all") && it.contains("sort=random") })
            // The Mix limit, not the full 12,596-track membership -- the whole
            // point of re-issuing the query is to sample it rather than fetch it.
            assertEquals("2500", sampledSize)
        }

    @Test
    fun `a manual playlist over the limit is fetched whole and sampled here`() =
        runTest {
            val sizes = mutableListOf<String?>()
            routeBy { request ->
                val body =
                    if (request.requestUrl?.encodedPath == "/playlists/9") {
                        probe(leafCount = 4000, smart = false, content = null)
                    } else {
                        sizes += request.getHeader("X-Plex-Container-Size")
                        emptyTracks
                    }
                MockResponse().setResponseCode(200).setBody(body)
            }

            PlexBrowseRepository().getPlaylistTracksForShuffle("9").get()

            // The whole thing, because there is no server-side way to sample it.
            assertEquals(listOf("4000"), sizes)
        }

    @Test
    fun `an artist under the limit keeps its running order`() =
        runTest {
            val sorts = mutableListOf<String?>()
            routeBy { request ->
                val size = request.getHeader("X-Plex-Container-Size")
                if (size != "0") sorts += request.requestUrl?.queryParameter("sort")
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"MediaContainer":{"size":0,"totalSize":297}}""")
            }

            PlexBrowseRepository().getArtistTracks("55").get()

            // Unsorted, so turning the car's shuffle off mid-listen falls back
            // to the artist's real album order rather than one we invented.
            assertEquals(listOf<String?>(null), sorts)
        }

    @Test
    fun `an artist over the limit is sampled by the server`() =
        runTest {
            val sorts = mutableListOf<String?>()
            routeBy { request ->
                val size = request.getHeader("X-Plex-Container-Size")
                if (size != "0") sorts += request.requestUrl?.queryParameter("sort")
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"MediaContainer":{"size":0,"totalSize":9000}}""")
            }

            PlexBrowseRepository().getArtistTracks("55").get()

            assertEquals(listOf("random"), sorts)
        }

    @Test
    fun `a decade mix asks for the limit and never probes`() =
        runTest {
            val sizes = mutableListOf<String?>()
            routeBy { request ->
                sizes += request.getHeader("X-Plex-Container-Size")
                MockResponse().setResponseCode(200).setBody(emptyTracks)
            }

            PlexBrowseRepository().getDecadeTracksForShuffle("scope|1980").get()

            // One request. Both branches of the rule are sort=random here, so a
            // probe could not change what is asked for.
            assertEquals(listOf("2500"), sizes)
        }

    @Test
    fun `a hub mix asks for the limit`() =
        runTest {
            val sizes = mutableListOf<String?>()
            routeBy { request ->
                sizes += request.getHeader("X-Plex-Container-Size")
                MockResponse().setResponseCode(200).setBody(emptyTracks)
            }

            PlexBrowseRepository().getHubTracksForIds(listOf("1"), listOf("2")).get()

            assertEquals(listOf("2500"), sizes)
        }
}
