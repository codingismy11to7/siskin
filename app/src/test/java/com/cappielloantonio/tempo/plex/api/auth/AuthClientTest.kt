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
}
