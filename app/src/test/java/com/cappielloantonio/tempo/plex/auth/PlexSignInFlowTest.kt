package com.cappielloantonio.tempo.plex.auth

import com.cappielloantonio.tempo.plex.base.MediaContainer
import com.cappielloantonio.tempo.plex.base.PlexResponse
import com.cappielloantonio.tempo.plex.models.Connection
import com.cappielloantonio.tempo.plex.models.Directory
import com.cappielloantonio.tempo.plex.models.Pin
import com.cappielloantonio.tempo.plex.models.Resource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexSignInFlowTest {

    private fun pin(id: Long?, code: String?, qr: String? = null, expiresAt: String? = null) =
        Pin().apply {
            this.id = id
            this.code = code
            this.qr = qr
            this.expiresAt = expiresAt
        }

    private fun server(provides: String = "server") = Resource().apply {
        this.provides = provides
        this.connections = listOf(Connection().apply { uri = "https://server"; local = true })
    }

    private fun sections(vararg types: String) = PlexResponse().apply {
        mediaContainer = MediaContainer().apply {
            directory = types.mapIndexed { i, t ->
                Directory().apply { key = "$i"; type = t; title = "section $i" }
            }
        }
    }

    @Test
    fun awaitsApprovalOnceAPinExists() {
        val state = PlexSignInFlow.afterPinCreated(
            pin(1L, "ABCD", qr = "https://plex.tv/qr", expiresAt = "2026-07-27T12:00:00Z")
        )
        val awaiting = state as PlexSignInState.AwaitingApproval
        assertEquals("ABCD", awaiting.code)
        assertEquals("https://plex.tv/qr", awaiting.qrUrl)
        assertEquals(1785153600L, awaiting.expiresAtEpochSeconds)
    }

    @Test
    fun survivesAPinWithNoQrUrl() {
        // The code-plus-instructions text carries the screen on its own, so a
        // missing qr is not a failure -- the screen just hides the image.
        val state = PlexSignInFlow.afterPinCreated(pin(1L, "ABCD", qr = null))
        assertNull((state as PlexSignInState.AwaitingApproval).qrUrl)
        assertNull(state.expiresAtEpochSeconds)
    }

    @Test
    fun failsWhenPlexReturnsNoUsablePin() {
        assertTrue(PlexSignInFlow.afterPinCreated(null) is PlexSignInState.Failed)
        assertTrue(PlexSignInFlow.afterPinCreated(pin(null, "ABCD")) is PlexSignInState.Failed)
        assertTrue(PlexSignInFlow.afterPinCreated(pin(1L, null)) is PlexSignInState.Failed)
        assertTrue(PlexSignInFlow.afterPinCreated(pin(1L, "  ")) is PlexSignInState.Failed)
    }

    @Test
    fun holdsTheCurrentStateWhileThePinIsPending() {
        // Identity, not equality: the screen must not flicker or restart Glide.
        val current = PlexSignInState.AwaitingApproval("ABCD", null, null)
        assertSame(current, PlexSignInFlow.afterPinPoll(PlexPinState.Pending, current))
    }

    @Test
    fun movesToWorkWhenThePinIsApproved() {
        val current = PlexSignInState.AwaitingApproval("ABCD", null, null)
        assertSame(
            PlexSignInState.Working,
            PlexSignInFlow.afterPinPoll(PlexPinState.Authorized("tok"), current)
        )
    }

    @Test
    fun failsWhenThePinExpires() {
        val current = PlexSignInState.AwaitingApproval("ABCD", null, null)
        assertTrue(PlexSignInFlow.afterPinPoll(PlexPinState.Expired, current) is PlexSignInState.Failed)
    }

    @Test
    fun offersTheServerPickerEvenForASingleServer() {
        // The spec chooses always-show over auto-advance: there is no settings
        // screen, so a wrong auto-pick is unfixable short of reinstalling.
        val state = PlexSignInFlow.afterResources(listOf(server()))
        assertEquals(1, (state as PlexSignInState.ChoosingServer).servers.size)
    }

    @Test
    fun failsWhenTheAccountHasNoMediaServers() {
        assertTrue(PlexSignInFlow.afterResources(emptyList()) is PlexSignInState.Failed)
        assertTrue(PlexSignInFlow.afterResources(null) is PlexSignInState.Failed)
        assertTrue(PlexSignInFlow.afterResources(listOf(server("player"))) is PlexSignInState.Failed)
    }

    @Test
    fun offersTheLibraryPickerForMusicSectionsOnly() {
        // Plex reports a music section's type as "artist"; movie and show
        // sections are libraries this app cannot play.
        val state = PlexSignInFlow.afterSections(sections("movie", "artist", "show", "artist"))
        assertEquals(2, (state as PlexSignInState.ChoosingLibrary).sections.size)
    }

    @Test
    fun failsWhenTheServerHasNoMusicLibrary() {
        assertTrue(PlexSignInFlow.afterSections(sections("movie")) is PlexSignInState.Failed)
        assertTrue(PlexSignInFlow.afterSections(null) is PlexSignInState.Failed)
    }
}
