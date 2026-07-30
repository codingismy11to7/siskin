package com.cappielloantonio.tempo.repository

import com.cappielloantonio.tempo.plex.models.Connection
import com.cappielloantonio.tempo.plex.models.Resource
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryPickerRepositoryTest {

    private fun resource(name: String, id: String, provides: String, uri: String?) =
        Resource().apply {
            this.name = name
            clientIdentifier = id
            this.provides = provides
            connections = uri?.let { listOf(Connection().apply { this.uri = it }) }
        }

    @Test
    fun `keeps only media servers with a usable connection`() {
        val rows = LibraryPickerRepository.serverRows(
            listOf(
                resource("Basement", "a", "server", "http://pms:32400"),
                resource("Phone", "b", "player", "http://phone:32400"),
                resource("Unreachable", "c", "server", null)
            )
        )
        assertEquals(listOf("Basement"), rows.map { it.name })
        assertEquals(listOf("a"), rows.map { it.machineIdentifier })
    }

    @Test
    fun `an absent resource list is no servers, not a crash`() {
        assertEquals(emptyList<String>(), LibraryPickerRepository.serverRows(null).map { it.name })
    }

    @Test
    fun `the current library is ticked and others are not`() {
        assertEquals("✓ Music", LibraryPickerRepository.rowTitle("Music", true))
        assertEquals("Audiobooks", LibraryPickerRepository.rowTitle("Audiobooks", false))
    }

    @Test
    fun `library ids carry both server and section`() {
        assertEquals(
            "abc123|3",
            LibraryPickerRepository.libraryIdPayload("abc123", "3")
        )
    }
}
