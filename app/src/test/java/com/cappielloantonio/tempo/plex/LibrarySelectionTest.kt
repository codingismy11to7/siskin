package com.cappielloantonio.tempo.plex

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySelectionTest {

    private fun session(
        uri: String = "http://pms:32400",
        section: String = "3",
        machine: String? = "abc123"
    ) = PlexSession.from("acct", uri, section, null, machine)!!

    @Test
    fun `matches on machine identifier and section`() {
        assertTrue(LibrarySelection.isCurrent(session(), "abc123", "http://other", "3"))
    }

    @Test
    fun `does not match a different section on the same server`() {
        assertFalse(LibrarySelection.isCurrent(session(), "abc123", "http://other", "4"))
    }

    @Test
    fun `does not match the same section key on a different server`() {
        assertFalse(LibrarySelection.isCurrent(session(), "different", "http://other", "3"))
    }

    @Test
    fun `falls back to server uri when no machine identifier is stored`() {
        val stored = session(machine = null)
        assertTrue(LibrarySelection.isCurrent(stored, "abc123", "http://pms:32400", "3"))
    }

    @Test
    fun `fallback fails closed when the address has changed`() {
        val stored = session(machine = null)
        assertFalse(LibrarySelection.isCurrent(stored, "abc123", "http://relay", "3"))
    }

    @Test
    fun `nothing is current when signed out`() {
        assertFalse(LibrarySelection.isCurrent(null, "abc123", "http://pms:32400", "3"))
    }

    @Test
    fun `switching library on the same server keeps the queue`() {
        val old = session(section = "3")
        val new = session(section = "4")
        assertFalse(LibrarySelection.invalidatesQueue(old, new))
    }

    @Test
    fun `switching server discards the queue`() {
        val old = session(machine = "abc123")
        val new = session(uri = "http://other:32400", machine = "zzz999")
        assertTrue(LibrarySelection.invalidatesQueue(old, new))
    }

    @Test
    fun `a first ever selection has no queue to discard`() {
        assertFalse(LibrarySelection.invalidatesQueue(null, session()))
    }
}
