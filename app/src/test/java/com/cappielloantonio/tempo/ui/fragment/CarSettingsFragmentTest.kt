package com.cappielloantonio.tempo.ui.fragment

import android.os.Looper
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
import com.cappielloantonio.tempo.service.MediaBrowserTree
import com.cappielloantonio.tempo.ui.activity.CarHostActivity
import com.cappielloantonio.tempo.util.BrowseTabOrderFixture
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.Preferences
import com.cappielloantonio.tempo.util.StreamingCacheTestHooks
import com.cappielloantonio.tempo.viewmodel.PlexSignInViewModel
import com.google.android.material.materialswitch.MaterialSwitch
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
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

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
            .remove("streaming_cache_size")
            .remove("precache_tracks_count")
            .commit()
        BrowseTabOrderFixture.clearSavedOrder()
        session = CarHostTestFixture.arrange()
    }

    @After
    fun tearDown() {
        CarHostTestFixture.tearDown()
        StreamingCacheTestHooks.release()
    }

    private fun settingsScreen(): View {
        val controller = Robolectric.buildActivity(CarHostActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        return controller.get().findViewById(R.id.car_host_container)
    }

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

    private fun artistsByInitialSwitch(view: View) =
        switchLabelled(view, App.getInstance().getString(R.string.car_settings_artists_by_initial))

    private fun artistMixSwitch(view: View) = switchLabelled(view, App.getInstance().getString(R.string.car_settings_artist_mix))

    private fun artistRadioSwitch(view: View) = switchLabelled(view, App.getInstance().getString(R.string.car_settings_artist_radio))

    @Test
    fun `settings offers every toggle at its default`() {
        val screen = settingsScreen()

        assertEquals(5, switchesIn(screen).size)
        // The defaults differ, and each is a decision: continuous play is off
        // because reaching the end of a queue is not a request for more music,
        // and replay gain is off because a library carrying no ReplayGain tags
        // would pay the whole cost of the feature for none of its benefit.
        assertFalse(continuousPlaySwitch(screen).isChecked)
        assertFalse(replayGainSwitch(screen).isChecked)
        // On, unlike its neighbours: #87 is unreleased, so there is no install
        // whose behaviour the default has to preserve.
        assertTrue(artistsByInitialSwitch(screen).isChecked)
        // On because every install so far has shown the row.
        assertTrue(artistMixSwitch(screen).isChecked)
        // Off: radio is an addition, and every row pushes the albums down.
        assertFalse(artistRadioSwitch(screen).isChecked)
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
     * Every row shares one `addToggle` and one `choice_container`, and each
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
        assertTrue(Preferences.isArtistsByInitialEnabled())
        assertTrue(Preferences.isArtistMixEnabled())
        assertFalse(Preferences.isArtistRadioEnabled())
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
        val screen = settingsScreen()

        assertTrue(visibleTexts(screen).any { it.startsWith("2 GB") })
        assertTrue(visibleTexts(screen).contains("3 tracks"))
    }

    @Test
    fun `precache says it is paused while the cache is off`() {
        Preferences.setStreamingCacheSizeOverrideMegabytes(0L)
        val screen = settingsScreen()

        assertTrue(visibleTexts(screen).contains("Off"))
        assertTrue(visibleTexts(screen).contains("2 tracks · paused while the cache is off"))
    }

    @Test
    fun `tapping cache size opens its picker`() {
        val controller = Robolectric.buildActivity(CarHostActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = controller.get()
        val label = textsIn(activity.findViewById(R.id.car_host_container)).first { it.text.toString() == "Cache size" }
        (label.parent as View).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(activity.supportFragmentManager.findFragmentById(R.id.car_host_container) is ValuePickerFragment)
    }

    private fun customizeTabsValue(screen: View): String {
        val texts = visibleTexts(screen)
        val label = texts.indexOf(App.getContext().getString(R.string.car_settings_customize_tabs))
        assertTrue("Customize tabs row is missing from $texts", label >= 0)
        return texts[label + 1]
    }

    @Test
    fun `customize tabs shows the default tabs`() {
        assertEquals("Playlists · Artists · Albums", customizeTabsValue(settingsScreen()))
    }

    /** Resolved, not read raw: the save names two, and the car fills the third. */
    @Test
    fun `customize tabs shows a saved order as the car resolves it`() {
        Preferences.setBrowseTabOrder(listOf(Constants.DECADES_ID, Constants.ALBUMS_ID))

        assertEquals("Decades · Albums · Playlists", customizeTabsValue(settingsScreen()))
    }

    @Test
    fun `tapping customize tabs opens the tab-order screen`() {
        val controller = Robolectric.buildActivity(CarHostActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = controller.get()
        val label = activity.getString(R.string.car_settings_customize_tabs)
        (textsIn(activity.findViewById(R.id.car_host_container)).single { it.text.toString() == label }.parent as View)
            .performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(activity.supportFragmentManager.findFragmentById(R.id.car_host_container) is BrowseTabOrderFragment)
    }

    @Test
    fun `a one-member Home shows no Switch user row`() {
        HomeUsersRepository.fetch = { home("me") }

        val screen = settingsScreen()

        assertFalse(visibleTexts(screen).contains(App.getContext().getString(R.string.car_settings_switch_user)))
    }

    @Test
    fun `a larger Home shows who is listening and the Switch user row above Sign out`() {
        HomeUsersRepository.fetch = { home("codingismy11to7", "jill") }

        val screen = settingsScreen()
        val texts = visibleTexts(screen)

        val switchUser = App.getContext().getString(R.string.car_settings_switch_user)
        val listeningAs = App.getContext().getString(R.string.car_settings_listening_as, "codingismy11to7")
        val signOut = App.getContext().getString(R.string.car_settings_sign_out)
        assertEquals(listOf(switchUser, listeningAs, signOut), texts.subList(texts.indexOf(switchUser), texts.indexOf(signOut) + 1))
    }

    @Test
    fun `a failed Home lookup shows no Switch user row`() {
        HomeUsersRepository.fetch = { null }

        val screen = settingsScreen()

        assertFalse(visibleTexts(screen).contains(App.getContext().getString(R.string.car_settings_switch_user)))
    }

    @Test
    fun `a revisit shows the remembered Home before plex tv answers`() {
        HomeUsersRepository.fetch = { home("codingismy11to7", "jill") }
        settingsScreen()
        HomeUsersRepository.fetch = { awaitCancellation() }

        val texts = visibleTexts(settingsScreen())

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

        val screen = settingsScreen()

        assertFalse(visibleTexts(screen).contains(App.getContext().getString(R.string.car_settings_switch_user)))
    }

    /** Hiding a drawn row would slide Sign out up under the finger aimed at Switch user. */
    @Test
    fun `a failed lookup keeps a remembered Switch user row`() {
        HomeUsersRepository.fetch = { home("codingismy11to7", "jill") }
        settingsScreen()
        HomeUsersRepository.fetch = { null }

        val screen = settingsScreen()

        assertTrue(visibleTexts(screen).contains(App.getContext().getString(R.string.car_settings_switch_user)))
    }

    @Test
    fun `a failed lookup is not remembered over a good one`() {
        HomeUsersRepository.fetch = { home("codingismy11to7", "jill") }
        settingsScreen()
        HomeUsersRepository.fetch = { null }
        settingsScreen()
        HomeUsersRepository.fetch = { awaitCancellation() }

        val screen = settingsScreen()

        assertTrue(visibleTexts(screen).contains(App.getContext().getString(R.string.car_settings_switch_user)))
    }

    @Test
    fun `a Home down to one member hides a remembered Switch user row`() {
        HomeUsersRepository.fetch = { home("codingismy11to7", "jill") }
        settingsScreen()
        HomeUsersRepository.fetch = { home("codingismy11to7") }

        val screen = settingsScreen()

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
            val controller = Robolectric.buildActivity(CarHostActivity::class.java).setup()
            shadowOf(Looper.getMainLooper()).idle()
            val activity = controller.get()
            val sessionBefore = PlexApi().session
            val label = activity.getString(R.string.car_settings_switch_user)

            (textsIn(activity.findViewById(R.id.car_host_container)).single { it.text.toString() == label }.parent as View)
                .performClick()
            shadowOf(Looper.getMainLooper()).idle()

            val picker = activity.supportFragmentManager.findFragmentById(R.id.car_host_container)
            assertTrue("got $picker", picker is PlexSignInFragment)
            assertTrue("the sign-in screen was routed, not pushed", picker!!.requireArguments().getBoolean("pushed"))
            val viewModel = ViewModelProvider(activity)[PlexSignInViewModel::class.java]
            assertTrue("got ${viewModel.state.value}", viewModel.state.value is PlexSignInState.ChoosingUser)

            activity.onBackPressedDispatcher.onBackPressed()
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(activity.supportFragmentManager.findFragmentById(R.id.car_host_container) is CarSettingsFragment)
            assertEquals(sessionBefore, PlexApi().session)
        } finally {
            CarHostActivity.viewModelFactoryForTest = null
        }
    }
}
