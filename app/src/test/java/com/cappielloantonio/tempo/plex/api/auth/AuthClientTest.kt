package com.cappielloantonio.tempo.plex.api.auth

import arrow.core.Either
import com.cappielloantonio.tempo.plex.models.Connection
import com.cappielloantonio.tempo.plex.models.Pin
import com.cappielloantonio.tempo.plex.models.Resource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthClientTest {

    private fun connection(uri: String, local: Boolean, relay: Boolean) = Connection().apply {
        this.uri = uri
        this.local = local
        this.relay = relay
    }

    private fun resource(vararg connections: Connection) = Resource().apply {
        this.connections = connections.toList()
    }

    // Choosing *which* connection to talk to a server on lives in ServerProbe and
    // is tested there, against real sockets -- it is a reachability question, and
    // no amount of ranking the payload answers it.

    @Test
    fun parsesTheIso8601ExpiryIntoEpochSeconds() {
        val pin = Pin().apply { expiresAt = "2026-07-27T12:00:00Z" }
        assertEquals(1785153600L, AuthClient.expiresAtEpochSeconds(pin))
    }

    @Test
    fun returnsNullExpiryForAnAbsentOrUnparseableTimestamp() {
        assertNull(AuthClient.expiresAtEpochSeconds(Pin()))
        assertNull(AuthClient.expiresAtEpochSeconds(Pin().apply { expiresAt = "not a date" }))
    }

    private fun server(provides: String?, uri: String? = "https://server") = Resource().apply {
        this.provides = provides
        if (uri != null) this.connections = listOf(connection(uri, local = true, relay = false))
    }

    @Test
    fun keepsOnlyDevicesThatProvideAServer() {
        // /resources also returns players, controllers and the account's phones.
        val servers = AuthClient.mediaServers(
            listOf(server("server"), server("player"), server("client,player"))
        )
        assertEquals(1, servers.size)
    }

    @Test
    fun readsServerOutOfACommaSeparatedCapabilityList() {
        assertEquals(1, AuthClient.mediaServers(listOf(server("server,player"))).size)
        assertEquals(1, AuthClient.mediaServers(listOf(server("player, server"))).size)
    }

    @Test
    fun dropsServersWithNoUsableConnection() {
        // A server advertising no address at all cannot be probed, so it is no more
        // choosable than one that is absent.
        assertEquals(0, AuthClient.mediaServers(listOf(server("server", uri = null))).size)
    }

    @Test
    fun handlesAnAbsentOrEmptyResourceList() {
        assertEquals(0, AuthClient.mediaServers(null).size)
        assertEquals(0, AuthClient.mediaServers(emptyList()).size)
    }

    // ── CreatedPin: the refinement createPin performs ──────────────────

    @Test
    fun createdPinRejectsAPinWithNoId() {
        val pin = Pin().apply { code = "ABCD" }

        assertEquals(
            Either.Left(CreatePinError.NoPinCode),
            AuthClient.validate(pin)
        )
    }

    @Test
    fun createdPinRejectsAPinWithNoCode() {
        val pin = Pin().apply { id = 42L }

        assertEquals(
            Either.Left(CreatePinError.NoPinCode),
            AuthClient.validate(pin)
        )
    }

    @Test
    fun createdPinRejectsAPinWhoseCodeIsBlank() {
        val pin = Pin().apply { id = 42L; code = "   " }

        assertEquals(
            Either.Left(CreatePinError.NoPinCode),
            AuthClient.validate(pin)
        )
    }

    @Test
    fun createdPinCarriesIdAndCodeAsNonNull() {
        val pin = Pin().apply {
            id = 42L
            code = "ABCD"
            qr = "https://plex.tv/qr/ABCD"
            expiresAt = "2026-07-28T12:00:00Z"
        }

        val created = AuthClient.validate(pin).getOrNull()!!

        assertEquals(42L, created.id)
        assertEquals("ABCD", created.code)
        assertEquals("https://plex.tv/qr/ABCD", created.qrUrl)
        assertEquals(1785240000L, created.expiresAtEpochSeconds)
    }

    @Test
    fun createdPinTreatsABlankQrAsAbsent() {
        // The screen falls back to showing the short code alone; a blank string
        // would make it try to load an image from nowhere.
        val pin = Pin().apply { id = 42L; code = "ABCD"; qr = "  " }

        assertNull(AuthClient.validate(pin).getOrNull()!!.qrUrl)
    }

    @Test
    fun createdPinToleratesAnUnparseableExpiry() {
        // PlexPinState.shouldKeepPolling bounds the loop when this is null.
        val pin = Pin().apply { id = 42L; code = "ABCD"; expiresAt = "not-a-date" }

        assertNull(AuthClient.validate(pin).getOrNull()!!.expiresAtEpochSeconds)
    }
}
