package com.cappielloantonio.tempo.ui.fragment

import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import arrow.core.right
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.SectionKey
import com.cappielloantonio.tempo.plex.api.auth.AuthClient
import com.cappielloantonio.tempo.plex.auth.PlexSignInState
import com.cappielloantonio.tempo.plex.models.HomeUser
import com.cappielloantonio.tempo.plex.models.PlexAccount
import com.cappielloantonio.tempo.repository.HomeUsersRepository
import com.cappielloantonio.tempo.service.BrowseTreeInvalidator
import com.cappielloantonio.tempo.ui.activity.CarHostActivity
import com.cappielloantonio.tempo.util.BrowseTabOrderFixture
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.Preferences
import com.cappielloantonio.tempo.util.StreamingCacheTestHooks
import com.cappielloantonio.tempo.viewmodel.PlexSignInViewModel
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.navigationrail.NavigationRailView
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

/**
 * Settings is reached by state: CarHostActivity.onCreate calls
 * open(forceSignIn = false), a session exists in preferences -- which is what
 * setUp() arranges -- so the ViewModel publishes Connected and the activity
 * routes this fragment into car_host_container.
 *
 * Driven through the activity rather than a FragmentScenario precisely because
 * that routing is part of what makes this screen appear at all. `setup()` is
 * what makes it happen: the activity observes with itself as the LifecycleOwner,
 * so nothing is delivered until STARTED and a `create()`-only controller would
 * find an empty container.
 *
 * This is the test #72 is actually about. The complaint was not that continuous
 * play defaulted on; it was that nothing could turn it off. A default flip alone
 * would satisfy PreferencesContinuousPlayTest and still leave that true.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class CarSettingsFragmentTest {
    private lateinit var session: MediaLibrarySession

    @Before
    fun setUp() {
        // Robolectric keeps App's SharedPreferences in a static field between
        // methods, and the session and every toggle are read out of it.
        App
            .getInstance()
            .preferences
            .edit()
            .remove("continuous_play")
            .remove("replay_gain_mode")
            .remove("artists_by_initial")
            .remove("artist_mix")
            .remove("artist_radio")
            .remove("select_library")
            .remove("streaming_cache_size")
            .remove("precache_tracks_count")
            .remove("crossfade")
            .commit()
        BrowseTabOrderFixture.clearSavedOrder()
        session = CarHostTestFixture.arrange()
    }

    @After
    fun tearDown() {
        Preferences.setSelectLibraryEnabled(false)
        CarHostTestFixture.tearDown()
        StreamingCacheTestHooks.release()
    }

    /**
     * Settings opened on [category]. Rows in other categories are built but
     * hidden, so a check on visible text has to be made in the row's category
     * or it passes for the wrong reason.
     */
    private fun settingsActivity(category: Int = R.id.settings_category_playback): CarHostActivity {
        val activity = Robolectric.buildActivity(CarHostActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        select(activity, category)
        return activity
    }

    private fun settingsScreen(category: Int = R.id.settings_category_playback): View =
        settingsActivity(category).findViewById(R.id.car_host_container)

    private fun select(
        activity: CarHostActivity,
        category: Int,
    ) {
        activity.findViewById<NavigationRailView>(R.id.settings_rail).selectedItemId = category
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun tapRow(
        activity: CarHostActivity,
        label: String,
    ) {
        val text = textsIn(activity.findViewById(R.id.car_host_container)).single { it.text.toString() == label }
        // A value row nests its label beside the chevron, so the row is not
        // always the label's parent, and a button is its own label: the first
        // clickable view from the label up is the target.
        generateSequence(text as View) { it.parent as? View }.first { it.isClickable }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun settings(activity: CarHostActivity) =
        activity.supportFragmentManager.findFragmentById(R.id.car_host_container) as CarSettingsFragment

    /** What Settings' detail pane is showing over the category rows, if anything. */
    private fun paneScreen(activity: CarHostActivity) = settings(activity).childFragmentManager.findFragmentById(R.id.pane_container)

    private fun switchesIn(view: View): List<MaterialSwitch> =
        when (view) {
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
    private fun switchLabelled(
        view: View,
        label: String,
    ): MaterialSwitch =
        switchesIn(view).single { toggle ->
            val row = toggle.parent as ViewGroup
            (0 until row.childCount)
                .map { row.getChildAt(it) }
                .filterIsInstance<TextView>()
                .any { it.text.toString() == label }
        }

    private fun continuousPlaySwitch(view: View) = switchLabelled(view, App.getInstance().getString(R.string.car_settings_continuous_play))

    private fun replayGainSwitch(view: View) = switchLabelled(view, App.getInstance().getString(R.string.car_settings_replay_gain))

    private fun crossfadeSwitch(view: View) = switchLabelled(view, App.getInstance().getString(R.string.car_settings_crossfade))

    private fun artistsByInitialSwitch(view: View) =
        switchLabelled(view, App.getInstance().getString(R.string.car_settings_artists_by_initial))

    private fun artistMixSwitch(view: View) = switchLabelled(view, App.getInstance().getString(R.string.car_settings_artist_mix))

    private fun artistRadioSwitch(view: View) = switchLabelled(view, App.getInstance().getString(R.string.car_settings_artist_radio))

    private fun selectLibrarySwitch(view: View) = switchLabelled(view, App.getInstance().getString(R.string.car_settings_select_library))

    @Test
    fun `settings offers every toggle at its default`() {
        val screen = settingsScreen()

        assertEquals(7, switchesIn(screen).size)
        // The defaults differ, and each is a decision: continuous play is off
        // because reaching the end of a queue is not a request for more music,
        // and replay gain is off because a library carrying no ReplayGain tags
        // would pay the whole cost of the feature for none of its benefit.
        assertFalse(continuousPlaySwitch(screen).isChecked)
        assertFalse(replayGainSwitch(screen).isChecked)
        // On, matching Plexamp.
        assertTrue(crossfadeSwitch(screen).isChecked)
        // On, unlike its neighbours: #87 is unreleased, so there is no install
        // whose behaviour the default has to preserve.
        assertTrue(artistsByInitialSwitch(screen).isChecked)
        // On because every install so far has shown the row.
        assertTrue(artistMixSwitch(screen).isChecked)
        // Off: radio is an addition, and every row pushes the albums down.
        assertFalse(artistRadioSwitch(screen).isChecked)
        // Off for every install: most accounts have one library, and the row
        // then has nothing to offer.
        assertFalse(selectLibrarySwitch(screen).isChecked)
    }

    @Test
    fun `the continuous play switch reflects a preference that is already on`() {
        Preferences.setContinuousPlayEnabled(true)

        assertTrue(continuousPlaySwitch(settingsScreen()).isChecked)
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
     * Every row shares one `addToggle`, and each closes over its own
     * preference. A row that wrote a neighbour's key would
     * still look right on screen -- the switch it moved is the one it was built
     * with -- so nothing else here would catch it.
     */
    @Test
    fun `a row writes only its own preference`() {
        val screen = settingsScreen()

        (replayGainSwitch(screen).parent as View).performClick()

        assertTrue(Preferences.isReplayGainEnabled())
        assertFalse(Preferences.isContinuousPlayEnabled())
        assertTrue(Preferences.isArtistsByInitialEnabled())
        assertTrue(Preferences.isArtistMixEnabled())
        assertFalse(Preferences.isArtistRadioEnabled())
        assertFalse(Preferences.isSelectLibraryEnabled())
    }

    @Test
    fun `tapping the select library row turns it on and puts it under More`() {
        val toggle = selectLibrarySwitch(settingsScreen())

        (toggle.parent as View).performClick()

        assertTrue(Preferences.isSelectLibraryEnabled())
        assertTrue(selectLibrarySwitch(settingsScreen()).isChecked)
        assertTrue(BrowseTabOrderFixture.moreChildren().contains(Constants.SELECT_LIBRARY_ID))

        // The car caches More and will not re-fetch it on the way back from
        // Settings unless told; invalidateNode posts to the main looper. The
        // root's children did not change, so it gets no notification.
        shadowOf(Looper.getMainLooper()).idle()
        verify(session).notifyChildrenChanged(eq(Constants.MORE_ID), eq(0), eq(null))
        verify(session, never()).notifyChildrenChanged(eq(Constants.ROOT_ID), any(), eq(null))
    }

    @Test
    fun `tapping the select library row twice takes it back out of More`() {
        (selectLibrarySwitch(settingsScreen()).parent as View).performClick()
        (selectLibrarySwitch(settingsScreen()).parent as View).performClick()

        assertFalse(Preferences.isSelectLibraryEnabled())
        assertFalse(selectLibrarySwitch(settingsScreen()).isChecked)
        assertFalse(BrowseTabOrderFixture.moreChildren().contains(Constants.SELECT_LIBRARY_ID))

        // Once per tap: taking a row out of the car's cached list matters as
        // much as adding one.
        shadowOf(Looper.getMainLooper()).idle()
        verify(session, times(2)).notifyChildrenChanged(eq(Constants.MORE_ID), eq(0), eq(null))
    }

    @Test
    fun `the select library row sits right after customize tabs`() {
        val texts = visibleTexts(settingsScreen(R.id.settings_category_browse))
        val tabs = texts.indexOf(App.getContext().getString(R.string.car_settings_customize_tabs))

        // +2: the Customize tabs row carries its value line beneath its label.
        assertEquals(App.getContext().getString(R.string.car_settings_select_library), texts[tabs + 2])
    }

    @Test
    fun `tapping the artist mix row turns it off`() {
        BrowseTreeInvalidator.rememberArtistPage(Constants.ARTIST_ID + "15100")
        val toggle = artistMixSwitch(settingsScreen())

        (toggle.parent as View).performClick()

        assertFalse(Preferences.isArtistMixEnabled())
        assertFalse(artistMixSwitch(settingsScreen()).isChecked)
        assertFalse(toggle.isClickable)

        // Settings is usually opened from an artist page, which the car shows
        // again from its cache on the way back unless it is told otherwise.
        shadowOf(Looper.getMainLooper()).idle()
        verify(session).notifyChildrenChanged(eq(Constants.ARTIST_ID + "15100"), any(), eq(null))
    }

    @Test
    fun `tapping the artist radio row turns it on and refreshes artist pages`() {
        BrowseTreeInvalidator.rememberArtistPage(Constants.ARTIST_ID + "15100")
        val toggle = artistRadioSwitch(settingsScreen())

        (toggle.parent as View).performClick()

        assertTrue(Preferences.isArtistRadioEnabled())
        assertTrue(artistRadioSwitch(settingsScreen()).isChecked)
        shadowOf(Looper.getMainLooper()).idle()
        verify(session).notifyChildrenChanged(eq(Constants.ARTIST_ID + "15100"), any(), eq(null))
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
        verify(session).notifyChildrenChanged(eq(Constants.ARTISTS_ID), any(), eq(null))
    }

    private fun home(vararg titles: String) =
        HomeUsersRepository.Home(
            users =
                titles.map { t ->
                    HomeUser().apply {
                        uuid = t
                        title = t
                    }
                },
            current =
                PlexAccount().apply {
                    uuid = titles.first()
                    title = titles.first()
                },
        )

    private fun textsIn(view: View): List<TextView> =
        when (view) {
            is TextView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { textsIn(view.getChildAt(it)) }
            else -> emptyList()
        }

    private fun visibleTexts(view: View) = textsIn(view).filter { it.isShown }.map { it.text.toString() }

    @Test
    fun `the cache rows show their values`() {
        Preferences.setStreamingCacheSizeOverrideMegabytes(2048L)
        Preferences.setPrecacheTracksCount(3)
        val screen = settingsScreen(R.id.settings_category_storage)

        assertTrue(visibleTexts(screen).any { it.startsWith("2 GB") })
        assertTrue(visibleTexts(screen).contains("3 tracks"))
    }

    @Test
    fun `precache says it is paused while the cache is off`() {
        Preferences.setStreamingCacheSizeOverrideMegabytes(0L)
        val screen = settingsScreen(R.id.settings_category_storage)

        assertTrue(visibleTexts(screen).contains("Off"))
        assertTrue(visibleTexts(screen).contains("2 tracks · paused while the cache is off"))
    }

    /** In the pane, beside the rail, rather than over the whole screen. */
    @Test
    fun `tapping cache size opens its picker in the pane`() {
        val activity = settingsActivity(R.id.settings_category_storage)

        tapRow(activity, "Cache size")

        assertTrue(paneScreen(activity) is ValuePickerFragment)
        assertTrue(activity.supportFragmentManager.findFragmentById(R.id.car_host_container) is CarSettingsFragment)
        assertEquals(0, activity.supportFragmentManager.backStackEntryCount)
    }

    @Test
    fun `back closes an open picker and stays on its category`() {
        val activity = settingsActivity(R.id.settings_category_storage)
        tapRow(activity, "Cache size")

        activity.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(null, paneScreen(activity))
        assertFalse(activity.isFinishing)
        assertEquals(R.id.settings_category_storage, activity.findViewById<NavigationRailView>(R.id.settings_rail).selectedItemId)
        assertTrue(visibleTexts(activity.findViewById(R.id.car_host_container)).contains("Cache size"))
    }

    private fun helpButton(activity: CarHostActivity) = activity.findViewById<View>(R.id.settings_help)

    @Test
    fun `every category but About offers Help`() {
        val activity = settingsActivity()
        listOf(
            R.id.settings_category_playback,
            R.id.settings_category_browse,
            R.id.settings_category_storage,
            R.id.settings_category_account,
        ).forEach {
            select(activity, it)
            assertEquals(View.VISIBLE, helpButton(activity).visibility)
        }
        select(activity, R.id.settings_category_about)
        assertEquals(View.GONE, helpButton(activity).visibility)
    }

    @Test
    fun `Help opens the category's help in the pane`() {
        val activity = settingsActivity(R.id.settings_category_playback)

        helpButton(activity).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(paneScreen(activity) is SettingsHelpFragment)
        assertEquals(0, activity.supportFragmentManager.backStackEntryCount)
        val pane = activity.findViewById<View>(R.id.pane_container)
        assertEquals("Playback", pane.findViewById<TextView>(R.id.settings_help_heading).text.toString())
        val text = pane.findViewById<TextView>(R.id.settings_help_text).text.toString()
        assertTrue(text.contains(App.getInstance().getString(R.string.car_settings_crossfade)))
        assertFalse(text.contains(App.getInstance().getString(R.string.car_settings_cache_size)))
    }

    @Test
    fun `back closes Help and stays on its category`() {
        val activity = settingsActivity(R.id.settings_category_storage)
        helpButton(activity).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        activity.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(null, paneScreen(activity))
        assertFalse(activity.isFinishing)
        assertTrue(visibleTexts(activity.findViewById(R.id.car_host_container)).contains("Cache size"))
    }

    /** What the car's RotaryService injects for one detent of the controller. */
    private fun rotaryScroll(clockwise: Boolean): MotionEvent {
        val pointer = MotionEvent.PointerProperties().apply { id = 0 }
        val coords = MotionEvent.PointerCoords().apply { setAxisValue(MotionEvent.AXIS_VSCROLL, if (clockwise) -1f else 1f) }
        return MotionEvent.obtain(
            0,
            0,
            MotionEvent.ACTION_SCROLL,
            1,
            arrayOf(pointer),
            arrayOf(coords),
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_ROTARY_ENCODER,
            0,
        )
    }

    /** Help is only text, so the scroll view is all a rotary controller can stop on. */
    @Test
    fun `a rotary controller lands on Help's text, scrolls it, and back returns it to Help`() {
        val activity = settingsActivity(R.id.settings_category_browse)
        val help = helpButton(activity)
        assertTrue(help.requestFocus())

        help.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val scroll = activity.findViewById<View>(R.id.settings_help_scroll)
        assertTrue(scroll.isFocused)
        assertEquals(SettingsHelpFragment.ROTARY_VERTICALLY_SCROLLABLE, scroll.contentDescription)

        assertTrue(scroll.dispatchGenericMotionEvent(rotaryScroll(clockwise = true)))
        assertTrue(scroll.scrollY > 0)

        activity.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(help.isFocused)
    }

    /** A turn the text cannot follow is left for RotaryService to move focus with. */
    @Test
    fun `a rotary turn past the top of Help's text is not consumed`() {
        val activity = settingsActivity(R.id.settings_category_browse)
        helpButton(activity).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val scroll = activity.findViewById<View>(R.id.settings_help_scroll)

        assertFalse(scroll.dispatchGenericMotionEvent(rotaryScroll(clockwise = false)))
        assertEquals(0, scroll.scrollY)

        assertTrue(scroll.dispatchGenericMotionEvent(rotaryScroll(clockwise = true)))
        assertTrue(scroll.dispatchGenericMotionEvent(rotaryScroll(clockwise = false)))
        assertEquals(0, scroll.scrollY)
    }

    @Test
    fun `selecting another category closes Help`() {
        val activity = settingsActivity(R.id.settings_category_playback)
        helpButton(activity).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        select(activity, R.id.settings_category_storage)

        assertEquals(null, paneScreen(activity))
    }

    @Test
    fun `back with nothing open leaves settings`() {
        val activity = settingsActivity()

        activity.onBackPressedDispatcher.onBackPressed()

        assertTrue(activity.isFinishing)
    }

    /** The rows outlive the picker now, so nothing rebuilds them on the way back. */
    @Test
    fun `choosing a precache count closes the picker and shows the new count`() {
        val activity = settingsActivity(R.id.settings_category_storage)
        tapRow(activity, "Precache")

        tapRow(activity, "3 tracks")

        assertEquals(3, Preferences.getPrecacheTracksCount())
        assertEquals(null, paneScreen(activity))
        assertTrue(visibleTexts(activity.findViewById(R.id.car_host_container)).contains("3 tracks"))
    }

    @Test
    fun `selecting another category closes an open picker`() {
        val activity = settingsActivity(R.id.settings_category_storage)
        tapRow(activity, "Cache size")

        select(activity, R.id.settings_category_playback)

        assertEquals(null, paneScreen(activity))
        assertTrue(
            visibleTexts(activity.findViewById(R.id.car_host_container))
                .contains(App.getContext().getString(R.string.car_settings_continuous_play)),
        )
    }

    /** A day/night flip recreates the activity, and the car does that on its own. */
    @Test
    fun `recreation keeps the category and the open picker`() {
        val controller = Robolectric.buildActivity(CarHostActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        select(controller.get(), R.id.settings_category_storage)
        tapRow(controller.get(), "Cache size")

        controller.recreate()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = controller.get()

        assertEquals(R.id.settings_category_storage, activity.findViewById<NavigationRailView>(R.id.settings_rail).selectedItemId)
        assertTrue(paneScreen(activity) is ValuePickerFragment)
        assertTrue(paneScreen(activity)!!.requireView().isShown)
    }

    @Test
    fun `a second tap before the first lands opens nothing more`() {
        val activity = settingsActivity(R.id.settings_category_storage)
        val row =
            generateSequence(
                textsIn(activity.findViewById(R.id.car_host_container)).single { it.text.toString() == "Cache size" } as View,
            ) { it.parent as? View }.first { it.isClickable }

        row.performClick()
        row.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, settings(activity).childFragmentManager.backStackEntryCount)
    }

    @Test
    fun `tapping the selected category closes its open picker`() {
        val activity = settingsActivity(R.id.settings_category_storage)
        tapRow(activity, "Cache size")

        select(activity, R.id.settings_category_storage)

        assertEquals(null, paneScreen(activity))
        assertTrue(visibleTexts(activity.findViewById(R.id.car_host_container)).contains("Cache size"))
    }

    /** Hiding the rows drops a rotary controller's focus; closing gives it back. */
    @Test
    fun `focus returns to the row that opened a picker`() {
        val activity = settingsActivity(R.id.settings_category_storage)
        val row =
            generateSequence(
                textsIn(activity.findViewById(R.id.car_host_container)).single { it.text.toString() == "Precache" } as View,
            ) { it.parent as? View }.first { it.isClickable }
        assertTrue(row.requestFocus())

        row.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        activity.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(row.isFocused)
    }

    @Test
    fun `leaving a picker for another category keeps focus on the rail`() {
        val activity = settingsActivity(R.id.settings_category_storage)
        val row =
            generateSequence(
                textsIn(activity.findViewById(R.id.car_host_container)).single { it.text.toString() == "Precache" } as View,
            ) { it.parent as? View }.first { it.isClickable }
        assertTrue(row.requestFocus())
        row.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        // Not Playback: a view losing focus hands it to the first focusable
        // one, which is Playback's rail item, and would pass this by accident.
        val account = activity.findViewById<View>(R.id.settings_category_account)
        assertTrue(account.requestFocus())

        select(activity, R.id.settings_category_account)

        assertTrue(account.isFocused)
    }

    @Test
    fun `the rail lists the categories in order and opens on playback`() {
        val activity = Robolectric.buildActivity(CarHostActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val rail = activity.findViewById<NavigationRailView>(R.id.settings_rail)

        assertEquals(
            listOf(
                R.id.settings_category_playback,
                R.id.settings_category_browse,
                R.id.settings_category_storage,
                R.id.settings_category_account,
                R.id.settings_category_about,
            ),
            (0 until rail.menu.size()).map { rail.menu.getItem(it).itemId },
        )
        assertEquals(R.id.settings_category_playback, rail.selectedItemId)
    }

    @Test
    fun `each category shows its own rows and hides the others`() {
        val context = App.getContext()
        val rowOf =
            mapOf(
                R.id.settings_category_playback to context.getString(R.string.car_settings_continuous_play),
                R.id.settings_category_browse to context.getString(R.string.car_settings_customize_tabs),
                R.id.settings_category_storage to context.getString(R.string.car_settings_cache_size),
                R.id.settings_category_account to context.getString(R.string.car_settings_sign_out),
                R.id.settings_category_about to context.getString(R.string.car_settings_about_credits),
            )
        val activity = settingsActivity()

        for ((category, row) in rowOf) {
            select(activity, category)
            val texts = visibleTexts(activity.findViewById(R.id.car_host_container))
            assertEquals("in $category", listOf(row), rowOf.values.filter { it in texts })
        }
    }

    @Test
    fun `About shows the build and when it landed`() {
        val texts = visibleTexts(settingsScreen(R.id.settings_category_about))

        assertTrue(texts.contains(App.getContext().getString(R.string.app_name)))
        assertTrue(texts.contains(versionLabel))
        assertTrue("got $texts", texts.any { it.startsWith("Updated ") })
    }

    /** About's version is the debug screen's entry point now, and there is one. */
    @Test
    fun `Account has no version line`() {
        assertFalse(visibleTexts(settingsScreen(R.id.settings_category_account)).contains(versionLabel))
    }

    /** The URL is printed under the code, so the QR is a shortcut and never the only way there. */
    @Test
    fun `Credits opens a dialog with the credits URL spelled out`() {
        val activity = settingsActivity(R.id.settings_category_about)

        tapRow(activity, activity.getString(R.string.car_settings_about_credits))

        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog.isShowing)
        assertEquals(CREDITS_URL, dialog.findViewById<TextView>(R.id.credits_url).text.toString())
    }

    /** A day/night switch recreates the activity, and a bare dialog would leak and vanish. */
    @Test
    fun `the Credits dialog survives the activity being recreated`() {
        val controller = Robolectric.buildActivity(CarHostActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        select(controller.get(), R.id.settings_category_about)
        tapRow(controller.get(), controller.get().getString(R.string.car_settings_about_credits))

        controller.recreate()
        shadowOf(Looper.getMainLooper()).idle()

        val credits =
            settings(controller.get()).childFragmentManager.findFragmentByTag(CreditsDialogFragment.TAG) as CreditsDialogFragment
        val dialog = credits.requireDialog()
        assertTrue(dialog.isShowing)
        assertEquals(CREDITS_URL, dialog.findViewById<TextView>(R.id.credits_url).text.toString())
    }

    private fun customizeTabsValue(screen: View): String {
        val texts = visibleTexts(screen)
        val label = texts.indexOf(App.getContext().getString(R.string.car_settings_customize_tabs))
        assertTrue("Customize tabs row is missing from $texts", label >= 0)
        return texts[label + 1]
    }

    @Test
    fun `customize tabs shows the default tabs`() {
        assertEquals("Playlists · Mixes · Radio", customizeTabsValue(settingsScreen(R.id.settings_category_browse)))
    }

    /** Resolved, not read raw: the save names two, and the car fills the third. */
    @Test
    fun `customize tabs shows a saved order as the car resolves it`() {
        Preferences.setBrowseTabOrder(listOf(Constants.DISCOVER_ID, Constants.ALBUMS_ID))

        assertEquals("Discover · Albums · Playlists", customizeTabsValue(settingsScreen(R.id.settings_category_browse)))
    }

    @Test
    fun `tapping customize tabs opens the tab-order screen in the pane`() {
        val activity = settingsActivity(R.id.settings_category_browse)

        tapRow(activity, activity.getString(R.string.car_settings_customize_tabs))

        assertTrue(paneScreen(activity) is BrowseTabOrderFragment)
        assertEquals(0, activity.supportFragmentManager.backStackEntryCount)
    }

    @Test
    fun `closing customize tabs shows the new order`() {
        val activity = settingsActivity(R.id.settings_category_browse)
        tapRow(activity, activity.getString(R.string.car_settings_customize_tabs))
        Preferences.setBrowseTabOrder(listOf(Constants.DISCOVER_ID, Constants.ALBUMS_ID))

        activity.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("Discover · Albums · Playlists", customizeTabsValue(activity.findViewById(R.id.car_host_container)))
    }

    @Test
    fun `a one-member Home shows no Switch user row`() {
        HomeUsersRepository.fetch = { home("me") }

        val screen = settingsScreen(R.id.settings_category_account)

        assertFalse(visibleTexts(screen).contains(App.getContext().getString(R.string.car_settings_switch_user)))
    }

    @Test
    fun `a larger Home shows who is listening and the Switch user row above Sign out`() {
        HomeUsersRepository.fetch = { home("codingismy11to7", "jill") }

        val screen = settingsScreen(R.id.settings_category_account)
        val texts = visibleTexts(screen)

        val switchUser = App.getContext().getString(R.string.car_settings_switch_user)
        val listeningAs = App.getContext().getString(R.string.car_settings_listening_as, "codingismy11to7")
        val signOut = App.getContext().getString(R.string.car_settings_sign_out)
        assertEquals(listOf(switchUser, listeningAs, signOut), texts.subList(texts.indexOf(switchUser), texts.indexOf(signOut) + 1))
    }

    @Test
    fun `a failed Home lookup shows no Switch user row`() {
        HomeUsersRepository.fetch = { null }

        val screen = settingsScreen(R.id.settings_category_account)

        assertFalse(visibleTexts(screen).contains(App.getContext().getString(R.string.car_settings_switch_user)))
    }

    @Test
    fun `a revisit shows the remembered Home before plex tv answers`() {
        HomeUsersRepository.fetch = { home("codingismy11to7", "jill") }
        settingsScreen()
        HomeUsersRepository.fetch = { awaitCancellation() }

        val texts = visibleTexts(settingsScreen(R.id.settings_category_account))

        assertTrue(texts.contains(App.getContext().getString(R.string.car_settings_switch_user)))
        assertTrue(texts.contains(App.getContext().getString(R.string.car_settings_listening_as, "codingismy11to7")))
    }

    @Test
    fun `a remembered Home is not shown once someone else is listening`() {
        HomeUsersRepository.fetch = { home("codingismy11to7", "jill") }
        settingsScreen()
        PlexApi().session =
            PlexSession(
                accountToken = "jill's token",
                serverUri = "https://example.invalid",
                musicSectionKey = SectionKey("1"),
                serverToken = null,
                owned = true,
            )
        HomeUsersRepository.fetch = { awaitCancellation() }

        val screen = settingsScreen(R.id.settings_category_account)

        assertFalse(visibleTexts(screen).contains(App.getContext().getString(R.string.car_settings_switch_user)))
    }

    /** Hiding a drawn row would slide Sign out up under the finger aimed at Switch user. */
    @Test
    fun `a failed lookup keeps a remembered Switch user row`() {
        HomeUsersRepository.fetch = { home("codingismy11to7", "jill") }
        settingsScreen()
        HomeUsersRepository.fetch = { null }

        val screen = settingsScreen(R.id.settings_category_account)

        assertTrue(visibleTexts(screen).contains(App.getContext().getString(R.string.car_settings_switch_user)))
    }

    @Test
    fun `a failed lookup is not remembered over a good one`() {
        HomeUsersRepository.fetch = { home("codingismy11to7", "jill") }
        settingsScreen()
        HomeUsersRepository.fetch = { null }
        settingsScreen()
        HomeUsersRepository.fetch = { awaitCancellation() }

        val screen = settingsScreen(R.id.settings_category_account)

        assertTrue(visibleTexts(screen).contains(App.getContext().getString(R.string.car_settings_switch_user)))
    }

    @Test
    fun `a Home down to one member hides a remembered Switch user row`() {
        HomeUsersRepository.fetch = { home("codingismy11to7", "jill") }
        settingsScreen()
        HomeUsersRepository.fetch = { home("codingismy11to7") }

        val screen = settingsScreen(R.id.settings_category_account)

        assertFalse(visibleTexts(screen).contains(App.getContext().getString(R.string.car_settings_switch_user)))
    }

    /**
     * The push has to land before the picker's first state does. Otherwise
     * the router sees that state with an empty back stack and swaps in a
     * routed sign-in screen, and back from the Home picker signs out on
     * screen instead of returning here.
     */
    @Test
    fun `Switch user pushes the Home picker and back returns to settings`() {
        val home = home("codingismy11to7", "jill")
        HomeUsersRepository.fetch = { home }
        val authClient =
            mock<AuthClient>().stub {
                on { homeUsers() } doReturn home.users.right()
                on { currentUser() } doReturn home.current.right()
            }
        CarHostActivity.viewModelFactoryForTest =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return PlexSignInViewModel(
                        RuntimeEnvironment.getApplication(),
                        authClient = authClient,
                        authClientFor = { authClient },
                    ) as T
                }
            }
        try {
            val activity = settingsActivity(R.id.settings_category_account)
            val sessionBefore = PlexApi().session

            tapRow(activity, activity.getString(R.string.car_settings_switch_user))

            val picker = activity.supportFragmentManager.findFragmentById(R.id.car_host_container)
            assertTrue("got $picker", picker is PlexSignInFragment)
            assertTrue("the sign-in screen was routed, not pushed", picker!!.requireArguments().getBoolean("pushed"))
            val viewModel = ViewModelProvider(activity)[PlexSignInViewModel::class.java]
            assertTrue("got ${viewModel.state.value}", viewModel.state.value is PlexSignInState.ChoosingUser)

            activity.onBackPressedDispatcher.onBackPressed()
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(activity.supportFragmentManager.findFragmentById(R.id.car_host_container) is CarSettingsFragment)
            assertEquals(sessionBefore, PlexApi().session)
            // The view was rebuilt on the way back; the category was not lost with it.
            assertEquals(R.id.settings_category_account, activity.findViewById<NavigationRailView>(R.id.settings_rail).selectedItemId)
            assertTrue(
                visibleTexts(
                    activity.findViewById(R.id.car_host_container),
                ).contains(activity.getString(R.string.car_settings_switch_user)),
            )
        } finally {
            CarHostActivity.viewModelFactoryForTest = null
        }
    }
}
