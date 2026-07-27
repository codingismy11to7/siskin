package com.cappielloantonio.tempo.plex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexIdentityTest {

    @Test
    fun includesEveryHeaderPlexRequires() {
        val headers = PlexIdentity.headers("cid-1", "1.2.3", null)
        assertEquals("cid-1", headers["X-Plex-Client-Identifier"])
        assertEquals("Siskin", headers["X-Plex-Product"])
        assertEquals("1.2.3", headers["X-Plex-Version"])
        assertEquals("Android", headers["X-Plex-Platform"])
        assertTrue(headers.containsKey("X-Plex-Device"))
        assertTrue(headers.containsKey("X-Plex-Model"))
    }

    @Test
    fun omitsTheTokenHeaderWhenSignedOut() {
        // Sending an empty X-Plex-Token is not the same as sending none; Plex
        // treats the empty value as a failed auth rather than an anonymous call.
        assertFalse(PlexIdentity.headers("cid-1", "1.2.3", null).containsKey("X-Plex-Token"))
        assertFalse(PlexIdentity.headers("cid-1", "1.2.3", "  ").containsKey("X-Plex-Token"))
    }

    @Test
    fun includesTheTokenHeaderWhenSignedIn() {
        assertEquals("tok123", PlexIdentity.headers("cid-1", "1.2.3", "tok123")["X-Plex-Token"])
    }

    @Test
    fun declaresAutomotiveAsTheDevice() {
        // Plex surfaces this string in the account's device list; "Android" alone
        // would be indistinguishable from the phone app.
        assertEquals("Automotive", PlexIdentity.headers("cid-1", "1.2.3", null)["X-Plex-Device"])
    }
}
