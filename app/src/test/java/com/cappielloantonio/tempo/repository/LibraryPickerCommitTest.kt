package com.cappielloantonio.tempo.repository

import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.SectionKey
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryPickerCommitTest {

    private val api = PlexApi()

    @Before
    fun setUp() {
        // Robolectric caches SharedPreferences statically across methods, so every
        // field this test depends on is reset rather than assumed absent.
        api.accountToken = "acct"
        api.session = PlexSession("acct", "http://pms:32400", SectionKey("3"), null, "abc123")
    }

    @Test
    fun `abandoning the picker leaves the session untouched`() {
        val before = api.session
        // .get() rather than fire-and-forget: getLibraries launches onto an IO
        // scope, so asserting immediately would run before the coroutine did
        // anything and pass no matter what the code does. Under Robolectric
        // there is no network, so getResources fails, the server is not found,
        // and the future completes with an error -- which is exactly the
        // abandoned-navigation path this asserts about.
        LibraryPickerRepository().getLibraries("some-other-server").get()
        assertEquals(before, api.session)
    }
}
