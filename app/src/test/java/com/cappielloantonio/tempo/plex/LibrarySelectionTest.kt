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
    fun `the server holding the current library is the current server`() {
        // Any section on it: the server row is ticked for the server, not for
        // whichever library inside it happens to be selected.
        assertTrue(LibrarySelection.isCurrentServer(session(section = "9"), "abc123"))
    }

    @Test
    fun `another server is not the current server`() {
        assertFalse(LibrarySelection.isCurrentServer(session(), "different"))
    }

    @Test
    fun `no server is current when the session predates machine identifiers`() {
        // Deliberately no serverUri fallback here: listing servers does not
        // probe, so there is no resolved address to compare against.
        assertFalse(LibrarySelection.isCurrentServer(session(machine = null), "abc123"))
    }

    @Test
    fun `no server is current when signed out`() {
        assertFalse(LibrarySelection.isCurrentServer(null, "abc123"))
    }

    @Test
    fun `a blank row identifier never ticks`() {
        assertFalse(LibrarySelection.isCurrentServer(session(), null))
        assertFalse(LibrarySelection.isCurrentServer(session(), ""))
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

    @Test
    fun `switching library on same server with null old identifier keeps queue`() {
        val old = session(section = "3", machine = null)
        val new = session(section = "4", machine = "zzz999")
        assertFalse(LibrarySelection.invalidatesQueue(old, new))
    }

    @Test
    fun `switching to different server with null old identifier discards queue`() {
        val old = session(uri = "http://pms:32400", section = "3", machine = null)
        val new = session(uri = "http://other:32400", section = "4", machine = "zzz999")
        assertTrue(LibrarySelection.invalidatesQueue(old, new))
    }

    @Test
    fun `switching library on same server with null new identifier keeps queue`() {
        val old = session(section = "3", machine = "abc123")
        val new = session(section = "4", machine = null)
        assertFalse(LibrarySelection.invalidatesQueue(old, new))
    }

    @Test
    fun `switching to different server with null new identifier discards queue`() {
        val old = session(uri = "http://pms:32400", section = "3", machine = "abc123")
        val new = session(uri = "http://other:32400", section = "4", machine = null)
        assertTrue(LibrarySelection.invalidatesQueue(old, new))
    }

    @Test
    fun `falls back to server uri when session has stored identifier but caller passes none`() {
        val stored = session(machine = "abc123")
        assertTrue(LibrarySelection.isCurrent(stored, null, "http://pms:32400", "3"))
    }

    @Test
    fun `fallback fails closed when stored identifier present but caller passes none and address changed`() {
        val stored = session(machine = "abc123")
        assertFalse(LibrarySelection.isCurrent(stored, null, "http://relay", "3"))
    }
}
