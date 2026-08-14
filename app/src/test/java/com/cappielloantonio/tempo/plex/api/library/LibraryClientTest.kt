package com.cappielloantonio.tempo.plex.api.library

import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.base.MediaContainer
import com.cappielloantonio.tempo.plex.base.PlexResponse
import com.cappielloantonio.tempo.plex.models.Directory
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric because PlexApi reads App.getInstance().preferences, which needs
 * a live Context -- required only by the getByHubKey case below, which builds
 * a real LibraryClient. See PlexRetrofitFactoryTest for the same pattern.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun clientAgainstServer(): LibraryClient {
        val api = PlexApi().apply { serverUri = server.url("/").toString() }
        return LibraryClient(api, api.serverUri, api.serverToken)
    }

    private fun response(vararg sections: Directory) = PlexResponse().apply {
        mediaContainer = MediaContainer().apply { directory = sections.toList() }
    }

    private fun section(key: String, type: String, title: String) = Directory().apply {
        this.key = key
        this.type = type
        this.title = title
    }

    @Test
    fun keepsOnlyMusicSections() {
        // Plex reports a music library's type as "artist", not "music".
        val sections = LibraryClient.musicSections(
            response(
                section("1", "movie", "Films"),
                section("2", "artist", "Music"),
                section("3", "show", "TV")
            )
        )
        assertEquals(1, sections.size)
        assertEquals("Music", sections.single().title)
    }

    @Test
    fun keepsEveryMusicSectionWhenThereAreSeveral() {
        val sections = LibraryClient.musicSections(
            response(section("1", "artist", "Music"), section("2", "artist", "Podcasts"))
        )
        assertEquals(2, sections.size)
    }

    @Test
    fun returnsEmptyRatherThanNullForAnAbsentOrEmptyContainer() {
        assertTrue(LibraryClient.musicSections(null).isEmpty())
        assertTrue(LibraryClient.musicSections(PlexResponse()).isEmpty())
        assertTrue(LibraryClient.musicSections(response()).isEmpty())
    }

    @Test
    fun ignoresSectionsMissingAKey() {
        // A section we cannot address is not browsable.
        val sections = LibraryClient.musicSections(
            response(section("", "artist", "Broken"), section("2", "artist", "Music"))
        )
        assertEquals(1, sections.size)
        assertEquals("2", sections.single().key)
    }

    @Test
    fun getByHubKeyNeverReachesTheServerWhenTheGuardRefusesTheKey() = runTest {
        // isSafeHubKey and getByPath are each tested on their own -- this is
        // the only thing that proves getByHubKey runs the guard *before* the
        // request, which is the actual security boundary: the account token
        // rides on every request PlexRetrofitFactory builds, so a request that
        // reaches the server at all has already lost, regardless of what
        // getByPath does with it. requestCount stays 0 rather than the enqueued
        // response going unread, which would pass even if the guard ran after.
        val result = clientAgainstServer().getByHubKey("//evil.example/library/sections/7/all")

        assertNull(result)
        assertEquals(0, server.requestCount)
    }
}
