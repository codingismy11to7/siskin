package com.cappielloantonio.tempo.ui.fragment

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.SectionKey
import com.cappielloantonio.tempo.service.BrowseTreeInvalidator
import com.cappielloantonio.tempo.service.MediaBrowserTree
import com.cappielloantonio.tempo.ui.activity.CarSignInActivity
import com.cappielloantonio.tempo.util.ConstantsAA
import com.cappielloantonio.tempo.util.Preferences
import com.google.android.material.materialswitch.MaterialSwitch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The Settings screen is the Connected branch of PlexSignInFragment.render(),
 * reached by CarSignInActivity.onCreate calling open(forceSignIn = false) while a
 * session exists in preferences -- which is what setUp() arranges.
 *
 * This is the test #72 is actually about. The complaint was not that continuous
 * play defaulted on; it was that nothing could turn it off. A default flip alone
 * would satisfy PreferencesContinuousPlayTest and still leave that true.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class PlexSignInSettingsTest {

    private lateinit var session: MediaLibrarySession

    @Before
    fun setUp() {
        // Robolectric keeps App's SharedPreferences in a static field between
        // methods, and the session and all three toggles are read out of it.
        App.getInstance().preferences.edit()
            .remove("continuous_play")
            .remove("car_shuffle")
            .remove("replay_gain_mode")
            .remove("artists_by_initial")
            .commit()
        PlexApi().session = PlexSession(
            accountToken = "t",
            serverUri = "https://example.invalid",
            musicSectionKey = SectionKey("1"),
            serverToken = null
        )

        // A live session is what makes the artists-by-initial row's
        // BrowseTreeInvalidator.invalidateNode() call do anything at all --
        // it returns early without one, so an assertion about it would pass
        // vacuously. Same pattern as BrowseTreeInvalidatorTest / LibraryPickerCommitTest.
        session = mock()
        MediaBrowserTree.initialize(App.getContext(), mock())
        BrowseTreeInvalidator.attach(session)
    }

    @After
    fun tearDown() {
        // BrowseTreeInvalidator is a process-wide singleton; leaving a mock
        // attached would leak into whatever test class runs next.
        BrowseTreeInvalidator.detach()
    }

    private fun settingsScreen(): View {
        val controller = Robolectric.buildActivity(CarSignInActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        return controller.get().findViewById(R.id.car_sign_in_container)
    }

    private fun switchesIn(view: View): List<MaterialSwitch> = when (view) {
        is MaterialSwitch -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { switchesIn(view.getChildAt(it)) }
        else -> emptyList()
    }

    /**
     * Selects by the row's label rather than by position. render() builds the
     * rows in source order, so an index would quietly follow any later
     * reordering and assert against the wrong switch -- and both switches are
     * MaterialSwitches with nothing else to tell them apart.
     */
    private fun switchLabelled(view: View, label: String): MaterialSwitch =
        switchesIn(view).single { toggle ->
            val row = toggle.parent as ViewGroup
            (0 until row.childCount)
                .map { row.getChildAt(it) }
                .filterIsInstance<TextView>()
                .any { it.text.toString() == label }
        }

    private fun continuousPlaySwitch(view: View) =
        switchLabelled(view, App.getInstance().getString(R.string.car_settings_continuous_play))

    private fun carShuffleSwitch(view: View) =
        switchLabelled(view, App.getInstance().getString(R.string.car_settings_car_shuffle))

    private fun replayGainSwitch(view: View) =
        switchLabelled(view, App.getInstance().getString(R.string.car_settings_replay_gain))

    private fun artistsByInitialSwitch(view: View) =
        switchLabelled(view, App.getInstance().getString(R.string.car_settings_artists_by_initial))

    @Test
    fun `settings offers every toggle at its default`() {
        val screen = settingsScreen()

        assertEquals(4, switchesIn(screen).size)
        // The defaults differ, and each is a decision: continuous play is off
        // because reaching the end of a queue is not a request for more music,
        // the car's shuffle is deferred to because that is what the app already
        // did, and replay gain is off because a library carrying no ReplayGain
        // tags would pay the whole cost of the feature for none of its benefit.
        assertFalse(continuousPlaySwitch(screen).isChecked)
        assertTrue(carShuffleSwitch(screen).isChecked)
        assertFalse(replayGainSwitch(screen).isChecked)
        // On, unlike its neighbours: #87 is unreleased, so there is no install
        // whose behaviour the default has to preserve.
        assertTrue(artistsByInitialSwitch(screen).isChecked)
    }

    @Test
    fun `the continuous play switch reflects a preference that is already on`() {
        Preferences.setContinuousPlayEnabled(true)

        assertTrue(continuousPlaySwitch(settingsScreen()).isChecked)
    }

    @Test
    fun `the car shuffle switch reflects a preference that has been turned off`() {
        Preferences.setCarShuffleEnabled(false)

        assertFalse(carShuffleSwitch(settingsScreen()).isChecked)
    }

    @Test
    fun `tapping the row turns continuous play on`() {
        val toggle = continuousPlaySwitch(settingsScreen())

        // The row owns the click, not the thumb -- a thumb is a phone-sized
        // target and this is a head unit. performClick() runs the row's listener
        // directly and never goes through touch dispatch, so what the two
        // assertions pin is that the one listener both writes the preference and
        // moves the switch to match: the switch can never show one thing while
        // the preference says another.
        //
        // Deliberately not toggle.performClick(): CompoundButton.performClick()
        // calls toggle() whatever isClickable says, so it would flip the switch
        // without writing anything and pin the opposite of the truth. That a tap
        // on the thumb reaches the row is a touch-dispatch property of a
        // non-clickable switch, and performClick() is not dispatch.
        (toggle.parent as View).performClick()

        assertTrue(Preferences.isContinuousPlayEnabled())
        assertTrue(toggle.isChecked)

        // Guards the desync a clickable switch would let through: a tap
        // landing on the thumb is consumed by CompoundButton.performClick,
        // which flips the switch without running the row's listener, so the
        // preference is never written and the switch snaps back at the next
        // render(). If addToggle ever drops isClickable = false, this fails
        // while the tap still "looks" like it worked.
        assertFalse(toggle.isClickable)
    }

    @Test
    fun `tapping the continuous play row again turns it back off`() {
        Preferences.setContinuousPlayEnabled(true)
        val toggle = continuousPlaySwitch(settingsScreen())

        (toggle.parent as View).performClick()

        assertFalse(Preferences.isContinuousPlayEnabled())
        assertFalse(toggle.isChecked)
    }

    /**
     * The car-shuffle row starts on, so its interesting direction is off -- which
     * is the tap that opts into a queue this app shuffles.
     */
    @Test
    fun `tapping the car shuffle row turns it off`() {
        val toggle = carShuffleSwitch(settingsScreen())

        (toggle.parent as View).performClick()

        assertFalse(Preferences.isCarShuffleEnabled())
        assertFalse(toggle.isChecked)
        assertFalse(toggle.isClickable)
    }

    @Test
    fun `tapping the replay gain row turns it on`() {
        val toggle = replayGainSwitch(settingsScreen())

        (toggle.parent as View).performClick()

        assertTrue(Preferences.isReplayGainEnabled())
        assertTrue(toggle.isChecked)
        assertFalse(toggle.isClickable)
    }

    @Test
    fun `tapping the replay gain row again turns it back off`() {
        Preferences.setReplayGainEnabled(true)
        val toggle = replayGainSwitch(settingsScreen())

        (toggle.parent as View).performClick()

        assertFalse(Preferences.isReplayGainEnabled())
        assertFalse(toggle.isChecked)
    }

    /**
     * Three rows now share one `addToggle` and one `choice_container`, and each
     * closes over its own preference. A row that wrote a neighbour's key would
     * still look right on screen -- the switch it moved is the one it was built
     * with -- so nothing else here would catch it.
     */
    @Test
    fun `a row writes only its own preference`() {
        val screen = settingsScreen()

        (replayGainSwitch(screen).parent as View).performClick()

        assertTrue(Preferences.isReplayGainEnabled())
        assertFalse(Preferences.isContinuousPlayEnabled())
        assertTrue(Preferences.isCarShuffleEnabled())
    }

    @Test
    fun `tapping the artists-by-initial row turns it off`() {
        val screen = settingsScreen()
        val toggle = artistsByInitialSwitch(screen)

        (toggle.parent as View).performClick()

        assertFalse(Preferences.isArtistsByInitialEnabled())
        assertFalse(artistsByInitialSwitch(settingsScreen()).isChecked)
        assertFalse(toggle.isClickable)

        // The preference write alone is not the point of this row: the car
        // caches the Artists tab's browse list and will not re-fetch it on its
        // own, so without this notification the tab keeps whichever shape it
        // was first loaded with and the toggle reads as doing nothing until
        // the next cold start. invalidateNode() posts to the main looper, so
        // this must come after idling it or it races the post and fails
        // intermittently.
        shadowOf(Looper.getMainLooper()).idle()
        verify(session).notifyChildrenChanged(eq(ConstantsAA.ARTISTS_ID), any(), eq(null))
    }
}
