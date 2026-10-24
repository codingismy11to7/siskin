package com.cappielloantonio.tempo.plex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlexApiTest {
    @Test
    fun usesTheServerTokenWhenTheResourceSuppliedOne() {
        // A shared server rejects the account token; only its own accessToken works.
        assertEquals("srv", PlexApi.serverAuthToken("srv", "acct", owned = false))
        assertEquals("srv", PlexApi.serverAuthToken("srv", "acct", owned = true))
    }

    @Test
    fun fallsBackToTheAccountTokenOnAnOwnedServer() {
        // A server you own has no per-resource accessToken and accepts the account one.
        assertEquals("acct", PlexApi.serverAuthToken(null, "acct", owned = true))
        assertEquals("acct", PlexApi.serverAuthToken("", "acct", owned = true))
        assertEquals("acct", PlexApi.serverAuthToken("   ", "acct", owned = true))
    }

    @Test
    fun neverFallsBackOnAServerTheAccountDoesNotOwn() {
        // The server would answer the account token with 401.
        assertNull(PlexApi.serverAuthToken(null, "acct", owned = false))
        assertNull(PlexApi.serverAuthToken("", "acct", owned = false))
    }

    @Test
    fun isNullWhenNeitherTokenExists() {
        assertNull(PlexApi.serverAuthToken(null, null, owned = true))
    }
}
