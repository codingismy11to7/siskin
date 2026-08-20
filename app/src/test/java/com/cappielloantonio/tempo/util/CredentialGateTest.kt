package com.cappielloantonio.tempo.util

import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.SectionKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric because CredentialGate.isSignedIn() reads through a real PlexApi,
 * which needs App.getInstance().preferences -- a live Context.
 */
@RunWith(RobolectricTestRunner::class)
class CredentialGateTest {
    private val completeSession =
        PlexSession(
            accountToken = "plex-account-token",
            serverUri = "https://192.168.1.10:32400",
            musicSectionKey = SectionKey("3"),
            serverToken = null,
        )

    // App caches SharedPreferences in a static field Robolectric does not reset
    // between methods, so every field is cleared explicitly before each test
    // rather than assumed absent.
    @Before
    fun clearSession() {
        PlexApi().session = null
        PlexApi().accountToken = null
    }

    @Test
    fun signedInWhenASessionIsPresent() {
        PlexApi().session = completeSession
        assertTrue(CredentialGate.isSignedIn())
    }

    @Test
    fun notSignedInWithoutAToken() {
        // AAOS exposes the browse tree straight after install: nothing is configured.
        assertFalse(CredentialGate.isSignedIn())
    }

    @Test
    fun notSignedInWithoutAServerUri() {
        // The PIN was approved but discovery never finished.
        PlexApi().accountToken = completeSession.accountToken
        assertFalse(CredentialGate.isSignedIn())
    }

    @Test
    fun notSignedInWithoutAMusicSection() {
        // A server was chosen but the library picker was never answered, so no
        // browse call could name a section to read.
        PlexApi().apply {
            accountToken = completeSession.accountToken
            serverUri = completeSession.serverUri
        }
        assertFalse(CredentialGate.isSignedIn())
    }

    @Test
    fun clearingTheSessionLeavesTheAccountTokenAloneButStillReadsAsSignedOut() {
        // The PIN grant is still valid after a sign-out, so the account token
        // survives -- but with no server or section, this must still read false.
        PlexApi().session = completeSession
        PlexApi().session = null

        assertFalse(CredentialGate.isSignedIn())
        assertTrue("accountToken must survive clearing the session", PlexApi().accountToken != null)
    }
}
