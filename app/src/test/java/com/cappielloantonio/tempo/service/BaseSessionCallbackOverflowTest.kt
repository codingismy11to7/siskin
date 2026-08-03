package com.cappielloantonio.tempo.service

import androidx.media3.common.Player
import com.cappielloantonio.tempo.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * What the car puts behind the overflow, and in what order.
 *
 * Robolectric because CommandButton.Builder reads real string resources and the
 * buttons are built in the constructor -- under `returnDefaultValues` every
 * display name would come back empty and the list would assemble regardless of
 * whether the resources exist.
 *
 * The order used to come from two preferences inherited from tempo, whose
 * settings screen this fork deleted; they always returned their defaults, so the
 * order was fixed and merely spelled indirectly. This pins the order it is now
 * spelled directly, and two absences: no rating button, because the car draws its
 * own control for that left of transport, and no instant mix, because whether the
 * queue extends itself is configuration and lives in Settings (#72).
 */
@RunWith(RobolectricTestRunner::class)
class BaseSessionCallbackOverflowTest {

    private fun overflowOf(player: Player): List<String> {
        val callback = object : BaseSessionCallback(RuntimeEnvironment.getApplication(), mock()) {
            fun overflow() = buildMediaButtonPreferences(player)
        }
        return callback.overflow().map { it.sessionCommand!!.customAction }
    }

    private fun player(
        repeatMode: Int = Player.REPEAT_MODE_OFF,
        shuffle: Boolean = false
    ): Player = mock<Player>().also {
        whenever(it.repeatMode).thenReturn(repeatMode)
        whenever(it.shuffleModeEnabled).thenReturn(shuffle)
    }

    @Test
    fun theOverflowIsRepeatThenShuffle() {
        assertEquals(
            listOf(
                Constants.CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_OFF,
                Constants.CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_ON
            ),
            overflowOf(player())
        )
    }

    @Test
    fun eachButtonReportsTheStateItWouldSwitchTo() {
        // The icons are the current state and the commands are the *next* one, so
        // a button that reported its own state would toggle nothing.
        assertEquals(
            listOf(
                Constants.CUSTOM_COMMAND_TOGGLE_REPEAT_MODE_ALL,
                Constants.CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_OFF
            ),
            overflowOf(player(repeatMode = Player.REPEAT_MODE_ALL, shuffle = true))
        )
    }

    @Test
    fun noRatingButtonIsOfferedAtAll() {
        // The regression this catches is a heart coming back beside the car's own
        // control, which is the redundancy the 2026-08-02 design removed.
        val everyState = listOf(
            player(),
            player(repeatMode = Player.REPEAT_MODE_ONE),
            player(repeatMode = Player.REPEAT_MODE_ALL, shuffle = true)
        ).flatMap { overflowOf(it) }

        assertEquals(emptyList<String>(), everyState.filter { "HEART" in it })
    }

    @Test
    fun noInstantMixButtonIsOfferedAtAll() {
        // The ⊖ half of the old pair had no branch in onCustomCommand and returned
        // ERROR_NOT_SUPPORTED; the ⚡ half truncated the queue after the current
        // track on a single tap, in a menu used at speed. Neither comes back.
        val everyState = listOf(
            player(),
            player(repeatMode = Player.REPEAT_MODE_ONE),
            player(repeatMode = Player.REPEAT_MODE_ALL, shuffle = true)
        ).flatMap { overflowOf(it) }

        assertEquals(emptyList<String>(), everyState.filter { "INSTANT_MIX" in it })
    }
}
