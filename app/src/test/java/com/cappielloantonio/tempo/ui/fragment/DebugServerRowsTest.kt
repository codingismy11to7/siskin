package com.cappielloantonio.tempo.ui.fragment

import com.cappielloantonio.tempo.plex.api.server.ServerAddressBook
import com.cappielloantonio.tempo.plex.models.Connection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugServerRowsTest {
    private val lan = "https://192-168-0-2.abc123.plex.direct:32400"
    private val docker = "https://172-17-0-1.abc123.plex.direct:32400"
    private val public = "https://107-135-52-98.abc123.plex.direct:17160"
    private val relay = "https://45-79-210-225.abc123.plex.direct:8443"

    private val known =
        ServerAddressBook.KnownAddresses(current = lan, direct = listOf(lan, docker, public), relay = listOf(relay))

    private fun connection(
        uri: String,
        local: Boolean,
        relay: Boolean = false,
    ) = Connection().apply {
        this.uri = uri
        this.local = local
        this.relay = relay
    }

    private val plexTv =
        listOf(
            connection(lan, local = true),
            connection(docker, local = true),
            connection(public, local = false),
            connection(relay, local = false, relay = true),
        )

    @Test
    fun plexTvsFlagsNameTheKind() {
        val kinds = DebugServerRows.build(known, plexTv).map { it.kind }

        assertEquals(listOf(AddressKind.LOCAL, AddressKind.LOCAL, AddressKind.REMOTE, AddressKind.RELAY), kinds)
    }

    @Test
    fun withoutPlexTvRowsFallBackToTheStoredTier() {
        val rows = DebugServerRows.build(known, connections = null)

        assertEquals(listOf(AddressKind.DIRECT, AddressKind.DIRECT, AddressKind.DIRECT, AddressKind.RELAY), rows.map { it.kind })
        assertTrue(rows.all { it.flags == null })
    }

    @Test
    fun plexDirectHostnamesDecodeToTheirAddress() {
        assertEquals("192.168.0.2:32400", DebugServerRows.hostPort(lan))
        assertEquals("45.79.210.225:8443", DebugServerRows.hostPort(relay))
    }

    @Test
    fun otherHostsAreShownAsTheyAre() {
        assertEquals("plex.example.com:443", DebugServerRows.hostPort("https://plex.example.com"))
        assertEquals("plex.example.com:32400", DebugServerRows.hostPort("https://plex.example.com:32400"))
    }

    @Test
    fun plexTvsOwnAddressAndPortArePreferred() {
        val uri = "https://abc123def.abc123.plex.direct:32400"
        val fromPlexTv =
            connection(uri, local = false).apply {
                address = "2001:db8::1"
                port = 32400
            }

        val row =
            DebugServerRows
                .build(ServerAddressBook.KnownAddresses(null, listOf(uri), emptyList()), listOf(fromPlexTv))
                .single()

        assertEquals("[2001:db8::1]:32400", row.hostPort)
        assertFalse(row.dockerRange)
    }

    @Test
    fun aCleartextAddressNeverReadsLikeItsHttpsTwin() {
        assertEquals("http://1.2.3.4:32400", DebugServerRows.hostPort("http://1.2.3.4:32400"))
        assertEquals("1.2.3.4:32400", DebugServerRows.hostPort("https://1-2-3-4.abc123.plex.direct:32400"))
    }

    @Test
    fun onlyTheCurrentAddressIsInUse() {
        val rows = DebugServerRows.build(known, plexTv)

        assertEquals(listOf(true, false, false, false), rows.map { it.inUse })
    }

    @Test
    fun aCurrentAddressMissingFromTheListStillGetsARow() {
        // A session written before the address book existed has no stored
        // candidates at all, only the address it uses.
        val rows =
            DebugServerRows.build(
                ServerAddressBook.KnownAddresses(current = lan, direct = emptyList(), relay = emptyList()),
                connections = null,
            )

        assertEquals(1, rows.size)
        assertTrue(rows.single().inUse)
        assertEquals(AddressKind.DIRECT, rows.single().kind)
    }

    @Test
    fun onlyTheDockerRangeIsFlagged() {
        val byUri = DebugServerRows.build(known, plexTv).associateBy { it.uri }

        assertTrue(byUri.getValue(docker).dockerRange)
        assertFalse(byUri.getValue(lan).dockerRange)
        assertFalse(byUri.getValue(public).dockerRange)
        assertFalse(
            DebugServerRows
                .build(
                    ServerAddressBook.KnownAddresses(
                        null,
                        listOf("https://172-32-0-1.abc123.plex.direct:32400"),
                        emptyList(),
                    ),
                    null,
                ).single()
                .dockerRange,
        )
    }

    @Test
    fun anAddressPlexTvDoesNotListHasNoFlags() {
        val rows = DebugServerRows.build(known, plexTv.filter { it.uri != public })

        val row = rows.single { it.uri == public }
        assertNull(row.flags)
        assertEquals(AddressKind.DIRECT, row.kind)
    }
}
