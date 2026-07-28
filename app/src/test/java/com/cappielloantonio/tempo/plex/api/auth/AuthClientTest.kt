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
        // Unreachable is indistinguishable from absent for the picker's purposes.
        assertEquals(0, AuthClient.mediaServers(listOf(server("server", uri = null))).size)
    }

    @Test
    fun handlesAnAbsentOrEmptyResourceList() {
        assertEquals(0, AuthClient.mediaServers(null).size)
        assertEquals(0, AuthClient.mediaServers(emptyList()).size)
    }
}
