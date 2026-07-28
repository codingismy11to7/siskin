package com.cappielloantonio.tempo.plex.api.auth

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

    @Test
    fun prefersALocalConnection() {
        // A LAN address avoids a round trip through Plex's infrastructure.
        val uri = AuthClient.bestConnectionUri(
            resource(
                connection("https://remote", local = false, relay = false),
                connection("https://local", local = true, relay = false)
            )
        )
        assertEquals("https://local", uri)
    }

    @Test
    fun prefersDirectRemoteOverRelay() {
        // Relay is bandwidth-limited by Plex and should be the last resort.
        val uri = AuthClient.bestConnectionUri(
            resource(
                connection("https://relay", local = false, relay = true),
                connection("https://direct", local = false, relay = false)
            )
        )
        assertEquals("https://direct", uri)
    }

    @Test
    fun fallsBackToRelayWhenItIsAllThereIs() {
        val uri = AuthClient.bestConnectionUri(resource(connection("https://relay", false, true)))
        assertEquals("https://relay", uri)
    }

    @Test
    fun returnsNullWhenThereAreNoConnections() {
        assertNull(AuthClient.bestConnectionUri(Resource()))
        assertNull(AuthClient.bestConnectionUri(resource()))
    }

    @Test
    fun ignoresConnectionsWithNoUri() {
        val uri = AuthClient.bestConnectionUri(
            resource(
                connection("", local = true, relay = false),
                connection("https://usable", local = false, relay = false)
            )
        )
        assertEquals("https://usable", uri)
    }

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
}
