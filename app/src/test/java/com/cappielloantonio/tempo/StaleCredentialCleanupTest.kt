package com.cappielloantonio.tempo

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class StaleCredentialCleanupTest {

    @Before
    fun setUp() {
        // Robolectric caches SharedPreferences statically across test methods, so
        // every field this test depends on is reset rather than assumed absent.
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences(
            context.packageName + "_preferences", Context.MODE_PRIVATE
        )
        prefs.edit()
            .remove("plex_token")
            .remove("plex_server_token")
            .commit()
    }

    @Test
    fun theOldTokenKeysAreDeletedRatherThanLeftToOutliveTheirReason() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences(
            context.packageName + "_preferences", Context.MODE_PRIVATE
        )
        prefs.edit().putString("plex_token", "left-behind")
            .putString("plex_server_token", "also-left-behind")
            .apply()

        App.clearStaleCredentialKeys(prefs)

        assertFalse(prefs.contains("plex_token"))
        assertFalse(prefs.contains("plex_server_token"))
    }
}
