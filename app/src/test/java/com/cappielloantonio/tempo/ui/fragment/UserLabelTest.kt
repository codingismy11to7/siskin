package com.cappielloantonio.tempo.ui.fragment

import com.cappielloantonio.tempo.plex.models.HomeUser
import org.junit.Assert.assertEquals
import org.junit.Test

class UserLabelTest {
    private fun member(
        name: String,
        pin: Boolean? = null,
    ) = HomeUser().apply {
        uuid = name
        title = name
        isProtected = pin
    }

    @Test
    fun `a plain member is just the name`() {
        assertEquals("Ann", PlexSignInFragment.userLabel(member("Ann"), null))
    }

    @Test
    fun `a protected member carries a lock`() {
        assertEquals("Ann  🔒", PlexSignInFragment.userLabel(member("Ann", pin = true), null))
    }

    @Test
    fun `the current member carries a check`() {
        assertEquals("Ann  🔒  ✓", PlexSignInFragment.userLabel(member("Ann", pin = true), "Ann"))
        assertEquals("Bo", PlexSignInFragment.userLabel(member("Bo"), "Ann"))
    }
}
