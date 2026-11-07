package com.cappielloantonio.tempo.util

import com.cappielloantonio.tempo.plex.FolderToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderKeyTest {
    @Test
    fun `a folder payload round-trips`() {
        val payload = FolderKey.of("abc123-7", FolderToken("17890"))
        assertEquals("abc123-7|17890", payload)
        assertEquals(FolderKey.Folder("abc123-7", FolderToken("17890")), FolderKey.parse(payload))
    }

    @Test
    fun `the root has no token`() {
        val payload = FolderKey.of("abc123-7", null)
        assertEquals("abc123-7|", payload)
        assertEquals(FolderKey.Folder("abc123-7", null), FolderKey.parse(payload))
    }

    @Test
    fun `malformed payloads parse to null`() {
        assertNull(FolderKey.parse("abc123-7"))
        assertNull(FolderKey.parse("|17890"))
        assertNull(FolderKey.parse("abc123-7|17a90"))
        assertNull(FolderKey.parse("abc123-7|1|2"))
        assertNull(FolderKey.parse("abc123-7|١٢"))
    }

    @Test
    fun `a window remainder splits into payload and start`() {
        val remainder = FolderKey.windowPrefix(FolderKey.of("abc123-7", FolderToken("5"))) + 50
        assertEquals("abc123-7|5" to 50, FolderKey.parseWindow(remainder))
    }

    @Test
    fun `a window of the root keeps the empty token`() {
        val remainder = FolderKey.windowPrefix(FolderKey.of("abc123-7", null)) + 100
        assertEquals("abc123-7|" to 100, FolderKey.parseWindow(remainder))
    }

    @Test
    fun `malformed window remainders parse to null`() {
        assertNull(FolderKey.parseWindow("abc123-7|5|x"))
        assertNull(FolderKey.parseWindow("abc123-7|5|-50"))
        assertNull(FolderKey.parseWindow("abc123-7|x|50"))
        assertNull(FolderKey.parseWindow("50"))
    }
}
