package com.cappielloantonio.tempo.plex.auth

import arrow.core.nonEmptyListOf
import com.cappielloantonio.tempo.plex.models.HomeUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeListenerTest {
    private fun user(uuid: String) =
        HomeUser().apply {
            this.uuid = uuid
            title = "$uuid's name"
        }

    @Test
    fun `a Home of two names whoever is listening`() {
        val listener = HomeListener.of(listOf(user("a"), user("b")), "b")!!

        assertEquals("b's name", listener.name)
        assertEquals("b", listener.currentUuid)
    }

    @Test
    fun `a Home of one is nothing to show`() {
        assertNull(HomeListener.of(listOf(user("a")), "a"))
    }

    @Test
    fun `nobody known to be listening is nothing to show`() {
        assertNull(HomeListener.of(listOf(user("a"), user("b")), null))
    }

    @Test
    fun `a listener outside the Home is nothing to show`() {
        // Marking nobody would let the picker "switch" to the current member.
        assertNull(HomeListener.of(listOf(user("a"), user("b")), "c"))
    }

    @Test
    fun `a listener built outside its Home names nobody rather than throwing`() {
        assertEquals("", HomeListener(nonEmptyListOf(user("a"), user("b")), "c").name)
    }

    @Test
    fun `only a Home of two or more has others`() {
        assertFalse(HomeListener.hasOthers(emptyList()))
        assertFalse(HomeListener.hasOthers(listOf(user("a"))))
        assertTrue(HomeListener.hasOthers(listOf(user("a"), user("b"))))
    }

    @Test
    fun `the saved member is carried through`() {
        assertEquals("a", HomeListener.of(listOf(user("a"), user("b")), "b", savedUuid = "a")!!.savedUuid)
        assertNull(HomeListener.of(listOf(user("a"), user("b")), "b")!!.savedUuid)
    }
}
