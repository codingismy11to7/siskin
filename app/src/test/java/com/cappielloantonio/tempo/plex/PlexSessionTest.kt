package com.cappielloantonio.tempo.plex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlexSessionTest {

    private val complete = PlexSession(
        accountToken = "account",
        serverUri = "https://server.example/",
        musicSectionKey = SectionKey("3"),
        serverToken = null
    )

    @Test
    fun readsBackEveryFieldItWasGiven() {
        val stored = PlexSession.from(
            accountToken = "account",
            serverUri = "https://server.example/",
            musicSectionKey = "3",
            serverToken = "shared"
        )

        assertEquals("account", stored!!.accountToken)
        assertEquals("https://server.example/", stored.serverUri)
        assertEquals(SectionKey("3"), stored.musicSectionKey)
        assertEquals("shared", stored.serverToken)
    }

    @Test
    fun aNullServerTokenIsLegalBecauseAnOwnedServerAcceptsTheAccountToken() {
        assertEquals(complete, PlexSession.from("account", "https://server.example/", "3", null))
    }

    @Test
    fun readsNullWhenTheAccountTokenIsMissingOrBlank() {
        assertNull(PlexSession.from(null, "https://server.example/", "3", null))
        assertNull(PlexSession.from("  ", "https://server.example/", "3", null))
    }

    @Test
    fun readsNullWhenTheServerUriIsMissingOrBlank() {
        assertNull(PlexSession.from("account", null, "3", null))
        assertNull(PlexSession.from("account", "  ", "3", null))
    }

    @Test
    fun readsNullWhenTheSectionKeyIsMissingOrBlank() {
        assertNull(PlexSession.from("account", "https://server.example/", null, null))
        assertNull(PlexSession.from("account", "https://server.example/", "  ", null))
    }

    @Test
    fun aPartialSetIsNotASession() {
        // The hazard CredentialGate's comment described: three fields written at
        // different moments, any subset of which could be read as signed in.
        assertNull(PlexSession.from("account", null, null, null))
        assertNull(PlexSession.from(null, null, "3", null))
    }

    @Test
    fun `carries the machine identifier when given one`() {
        val session = PlexSession.from("acct", "http://pms:32400", "3", null, "abc123")
        assertEquals("abc123", session?.machineIdentifier)
    }

    @Test
    fun `machine identifier is optional, like the server token`() {
        val session = PlexSession.from("acct", "http://pms:32400", "3", null, null)
        assertNotNull(session)
        assertNull(session?.machineIdentifier)
    }
}
