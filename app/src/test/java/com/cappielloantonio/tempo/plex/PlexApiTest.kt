package com.cappielloantonio.tempo.plex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlexApiTest {

    @Test
    fun usesTheServerTokenWhenTheResourceSuppliedOne() {
        // A shared server rejects the account token; only its own accessToken works.
        assertEquals("srv", PlexApi.serverTokenOrAccount("srv", "acct"))
    }

    @Test
    fun fallsBackToTheAccountTokenWhenTheResourceHasNone() {
        // A server you own has no per-resource accessToken and accepts the account one.
        assertEquals("acct", PlexApi.serverTokenOrAccount(null, "acct"))
        assertEquals("acct", PlexApi.serverTokenOrAccount("", "acct"))
        assertEquals("acct", PlexApi.serverTokenOrAccount("   ", "acct"))
    }

    @Test
    fun isNullWhenNeitherTokenExists() {
        assertNull(PlexApi.serverTokenOrAccount(null, null))
    }
}
