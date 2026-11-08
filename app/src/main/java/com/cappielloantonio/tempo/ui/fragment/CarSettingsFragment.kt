package com.cappielloantonio.tempo.ui.fragment

import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.BuildConfig
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.databinding.FragmentCarSettingsBinding
import com.cappielloantonio.tempo.interfaces.LoginHost
import com.cappielloantonio.tempo.repository.HomeUsersRepository
import com.cappielloantonio.tempo.service.BrowseTreeInvalidator
import com.cappielloantonio.tempo.util.BrowseTabOrder
import com.cappielloantonio.tempo.util.CacheSizeChoice
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.DownloadUtil
import com.cappielloantonio.tempo.util.Preferences
import com.cappielloantonio.tempo.util.StreamingCacheChoices
import com.cappielloantonio.tempo.viewmodel.PlexSignInViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the car shows once there is a session: a rail of [SettingsCategory]s and
 * the selected one's rows beside it. The pickers and Customize tabs open in that
 * pane rather than over the screen. See
 * docs/decisions/2026-11-07-settings-two-pane-design.md.
 *
 * Reached by state, not by a caller. [CarHostActivity] routes
 * [com.cappielloantonio.tempo.plex.auth.PlexSignInState.Connected] here and
 * everything else to [PlexSignInFragment] -- so signing out is not a navigation
 * this fragment performs, it is a state change that moves the activity off this
 * screen. See the activity's KDoc for why the routing lives there.
 *
 * Builds its rows once, in [onCreateView], rather than observing state (except
 * that Switch user's visibility waits on a plex.tv answer, and the cache usage
 * line on a read of the cache's index): the only state this screen is ever
 * shown for is Connected, and the one transition out of it is the one that
 * replaces this fragment. The rows that a pane screen changes are re-read when
 * it closes, since the view outlives it.
 *
 * Back closes an open pane screen. With none open, the callback is disabled and
 * back falls through to the platform default, finishing the activity --
 * [PlexSignInViewModel.handlesBackPress] reports false for Connected, and the
 * screen you land on should not be one you have to back out of twice.
 */
@UnstableApi
class CarSettingsFragment : Fragment() {
    private var bind: FragmentCarSettingsBinding? = null
    private lateinit var viewModel: PlexSignInViewModel
    private var category = SettingsCategory.PLAYBACK
    private var about: SettingsAbout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString(KEY_CATEGORY)?.let { category = SettingsCategory.valueOf(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_CATEGORY, category.name)
    }

    @OptIn(UnstableApi::class)
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        viewModel = ViewModelProvider(requireActivity())[PlexSignInViewModel::class.java]
        val bind = FragmentCarSettingsBinding.inflate(inflater, container, false)
        this.bind = bind

        val panes =
            SettingsCategory.entries.associateWith {
                LinearLayout(inflater.context)
                    .apply { orientation = LinearLayout.VERTICAL }
                    .also { pane -> bind.categoryContainer.addView(pane) }
            }
        val playback = panes.getValue(SettingsCategory.PLAYBACK)
        val browse = panes.getValue(SettingsCategory.BROWSE)
        val storage = panes.getValue(SettingsCategory.STORAGE)
        val account = panes.getValue(SettingsCategory.ACCOUNT)

        // Each row reads its preference here rather than holding any state.
        addToggle(
            playback,
            getString(R.string.car_settings_continuous_play),
            Preferences.isContinuousPlayEnabled(),
        ) { Preferences.setContinuousPlayEnabled(it) }

        addToggle(
            playback,
            getString(R.string.car_settings_replay_gain),
            Preferences.isReplayGainEnabled(),
        ) { Preferences.setReplayGainEnabled(it) }

        addToggle(
            playback,
            getString(R.string.car_settings_crossfade),
            Preferences.isCrossfadeEnabled(),
        ) { Preferences.setCrossfadeEnabled(it) }

        // Invalidates the Artists tab as well as writing the key. The car
        // caches a browse list and does not re-fetch it on back-navigation, so
        // without this the tab keeps whichever shape it was first loaded with
        // and the row reads as doing nothing until the next cold start.
        addToggle(
            browse,
            getString(R.string.car_settings_artists_by_initial),
            Preferences.isArtistsByInitialEnabled(),
        ) {
            Preferences.setArtistsByInitialEnabled(it)
            BrowseTreeInvalidator.invalidateNode(Constants.ARTISTS_ID, 0)
        }

        addToggle(
            browse,
            getString(R.string.car_settings_artist_mix),
            Preferences.isArtistMixEnabled(),
        ) {
            Preferences.setArtistMixEnabled(it)
            BrowseTreeInvalidator.invalidateArtistPages()
        }

        addToggle(
            browse,
            getString(R.string.car_settings_artist_radio),
            Preferences.isArtistRadioEnabled(),
        ) {
            Preferences.setArtistRadioEnabled(it)
            BrowseTreeInvalidator.invalidateArtistPages()
        }

        // Filled in by refreshValues, which watchPane runs once the rows exist.
        val context = requireContext()
        val cacheValue =
            addValueRow(storage, getString(R.string.car_settings_cache_size), "") {
                openInPane(ValuePickerFragment.newInstance(ValuePicker.CACHE_SIZE))
            }.value
        val precacheValue =
            addValueRow(storage, getString(R.string.car_settings_precache), "") {
                openInPane(ValuePickerFragment.newInstance(ValuePicker.PRECACHE))
            }.value

        val tabsValue =
            addValueRow(browse, getString(R.string.car_settings_customize_tabs), "") {
                openInPane(BrowseTabOrderFragment())
            }.value

        fun showTabs() {
            val tabs = BrowseTabOrder.rootTabs(BrowseTabOrder.resolve(Preferences.getBrowseTabOrder()))
            tabsValue.text = tabs.joinToString(" · ") { getString(BrowseTabOrderFragment.labelFor(it)) }
        }
        refreshValues = {
            showStorage(context, cacheValue, precacheValue)
            showTabs()
        }

        // Rebuilds the tree as well as writing the key: the tree is held in
        // memory, and the car keeps showing its cached More until told it changed.
        addToggle(
            browse,
            getString(R.string.car_settings_select_library),
            Preferences.isSelectLibraryEnabled(),
        ) {
            Preferences.setSelectLibraryEnabled(it)
            BrowseTreeInvalidator.invalidateMore()
        }

        // Shown only for a Home with more than one member. Drawn from the
        // remembered answer, so the row is usually in place before plex.tv
        // replies; a failed reply then changes nothing, since hiding a drawn
        // row would slide Sign out up under the finger aimed at it. One line,
        // ellipsized, so a long name or a rename cannot change the row's height.
        val switchUser = addValueRow(account, getString(R.string.car_settings_switch_user), "") { switchUser() }
        switchUser.value.maxLines = 1
        switchUser.value.ellipsize = TextUtils.TruncateAt.END

        fun showHome(home: HomeUsersRepository.Home?) {
            val shown = home != null && home.users.size >= 2
            if (shown) switchUser.value.text = getString(R.string.car_settings_listening_as, home.current.title.orEmpty())
            switchUser.row.visibility = if (shown) View.VISIBLE else View.GONE
        }
        showHome(HomeUsersRepository.remembered())

        addDestructiveChoice(account, getString(R.string.car_settings_sign_out)) {
            viewModel.signOut()
            (requireActivity() as LoginHost).onSignedOut()
        }

        // About's version is the debug screen's entry point. The debug screen
        // is pushed, so back returns here, on About. Executed now, as in
        // openInPane, so a second tap in the same frame pushes nothing more.
        about =
            SettingsAbout(panes.getValue(SettingsCategory.ABOUT), childFragmentManager) {
                if (parentFragmentManager.backStackEntryCount > 0) return@SettingsAbout
                parentFragmentManager
                    .beginTransaction()
                    .replace(R.id.car_host_container, CarDebugFragment())
                    .addToBackStack(null)
                    .commit()
                parentFragmentManager.executePendingTransactions()
            }

        viewLifecycleOwner.lifecycleScope.launch { HomeUsersRepository.refresh()?.let(::showHome) }

        buildRail(bind, panes)
        watchPane(bind)

        return bind.root
    }

    /**
     * The selected category lives on the fragment and in its saved state, not
     * the view: Switch user, the debug screen and a uiMode flip all rebuild the
     * view, and it has to reopen where it was left.
     *
     * Building shows the category without closing anything, since a pane screen
     * the child manager restored belongs to it. Choosing a category on the rail,
     * including the one already selected, closes whatever is open; otherwise a
     * pane screen stays beside the wrong rail item, or cannot be left by the rail.
     */
    private fun buildRail(
        bind: FragmentCarSettingsBinding,
        panes: Map<SettingsCategory, View>,
    ) {
        fun show(category: SettingsCategory) {
            this.category = category
            panes.forEach { (each, pane) -> pane.visibility = if (each == category) View.VISIBLE else View.GONE }
            bind.categoryHeading.setText(category.label)
            if (category == SettingsCategory.ABOUT) about?.render()
            bind.contentScroll.scrollTo(0, 0)
        }

        fun select(category: SettingsCategory) {
            // The opener is about to be hidden with its category, and focus
            // belongs on the rail item that was just pressed.
            if (category != this.category) paneOpener = null
            closePane()
            show(category)
        }
        val rail = bind.settingsRail
        SettingsCategory.entries.forEach {
            rail.menu.add(Menu.NONE, it.id, it.ordinal, it.label).setIcon(it.icon)
        }
        show(category)
        rail.selectedItemId = category.id
        rail.setOnItemSelectedListener { item ->
            select(SettingsCategory.entries.first { it.id == item.itemId })
            true
        }
        rail.setOnItemReselectedListener { select(category) }
    }

    private var paneChanged: FragmentManager.OnBackStackChangedListener? = null

    // The row that opened the pane screen, so a rotary controller lands back
    // on it when the screen closes. Null in touch mode, where nothing has focus.
    private var paneOpener: View? = null

    /**
     * Shows the pane screen in place of the category rows while one is open,
     * and lets back close it. Run once at the end, so a pane screen the child
     * manager restored with a rebuilt view is shown rather than hidden behind
     * the rows -- and so the rows' values are filled in.
     */
    private fun watchPane(bind: FragmentCarSettingsBinding) {
        val backClosesPane =
            object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() = closePane()
            }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backClosesPane)
        val listener =
            FragmentManager.OnBackStackChangedListener {
                val open = childFragmentManager.backStackEntryCount > 0
                backClosesPane.isEnabled = open
                bind.paneContainer.visibility = if (open) View.VISIBLE else View.GONE
                bind.categoryPane.visibility = if (open) View.GONE else View.VISIBLE
                if (open) {
                    bind.paneContainer.requestFocus()
                } else {
                    refreshValues()
                    paneOpener?.requestFocus()
                    paneOpener = null
                }
            }
        childFragmentManager.addOnBackStackChangedListener(listener)
        paneChanged = listener
        listener.onBackStackChanged()
    }

    private var refreshValues: () -> Unit = {}

    /**
     * Executed now rather than queued, so a second tap before the next frame
     * sees the first screen open and is ignored instead of stacking another.
     */
    private fun openInPane(fragment: Fragment) {
        if (childFragmentManager.backStackEntryCount > 0) return
        paneOpener = bind?.root?.findFocus()
        childFragmentManager
            .beginTransaction()
            .replace(R.id.pane_container, fragment)
            .addToBackStack(null)
            .commit()
        childFragmentManager.executePendingTransactions()
    }

    private fun closePane() {
        if (childFragmentManager.backStackEntryCount == 0) return
        childFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }

    private var usageRead: Job? = null

    /**
     * Fills in the Storage rows from preferences. Run when the view is built
     * and again when a pane screen closes, since the rows outlive it.
     *
     * Reading usage may build the cache, which reads its index from disk. It
     * lands in a line already laid out, so it changes text, not layout.
     * Skipped when the cache is off: that line shows no usage, so building the
     * cache would load its index for nothing. A failed read leaves the line
     * without a figure rather than taking the process down. A read still
     * running from the last call is cancelled, or it could land after this one
     * and show a size the cache no longer has.
     */
    private fun showStorage(
        context: Context,
        cacheValue: TextView,
        precacheValue: TextView,
    ) {
        usageRead?.cancel()
        usageRead = null
        val automatic = DownloadUtil.getAutomaticStreamingCacheSizeMegabytes(context)
        val cacheChoice = StreamingCacheChoices.current(Preferences.getStreamingCacheSizeOverrideMegabytes(), automatic)
        cacheValue.text = StreamingCacheChoices.cacheSizeSummary(resources, cacheChoice, usedBytes = null)
        precacheValue.text =
            StreamingCacheChoices.precacheSummary(
                resources,
                Preferences.getPrecacheTracksCount(),
                cacheOff = cacheChoice == CacheSizeChoice.Off,
            )
        if (cacheChoice == CacheSizeChoice.Off) return
        usageRead =
            viewLifecycleOwner.lifecycleScope.launch {
                val used =
                    try {
                        // On the resize thread, so it reads after any trim already
                        // queued -- and a cancelled read never parks a thread there.
                        withContext(DownloadUtil.getCacheWorkExecutor().asCoroutineDispatcher()) {
                            DownloadUtil.getStreamingCacheUsedBytes(context)
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        Log.w("CarSettingsFragment", "Could not read cache usage: $exception")
                        return@launch
                    }
                cacheValue.text = StreamingCacheChoices.cacheSizeSummary(resources, cacheChoice, used)
            }
    }

    /**
     * Pushed, as the debug screen's Choose server is, so back returns here.
     *
     * The push is executed before the state changes. `commit()` alone is
     * asynchronous, so the router would see the new state with an empty back
     * stack and replace the pushed screen with a routed one -- whose back
     * signs out on screen instead of returning here.
     */
    private fun switchUser() {
        parentFragmentManager
            .beginTransaction()
            .replace(R.id.car_host_container, PlexSignInFragment.pushed())
            .addToBackStack(null)
            .commit()
        parentFragmentManager.executePendingTransactions()
        viewModel.reopenUserPicker()
    }

    /** About's Updated line is relative, and Settings can sit behind the car's UI for hours. */
    override fun onResume() {
        super.onResume()
        if (category == SettingsCategory.ABOUT) about?.render()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        about = null
        paneChanged?.let(childFragmentManager::removeOnBackStackChangedListener)
        paneChanged = null
        paneOpener = null
        usageRead = null
        refreshValues = {}
        bind = null
    }

    private companion object {
        const val KEY_CATEGORY = "category"
    }
}

/** Settings' rail, in order. */
private enum class SettingsCategory(
    @IdRes val id: Int,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
) {
    PLAYBACK(R.id.settings_category_playback, R.string.car_settings_category_playback, R.drawable.ic_settings_playback),
    BROWSE(R.id.settings_category_browse, R.string.car_settings_category_browse, R.drawable.ic_browse_library),
    STORAGE(R.id.settings_category_storage, R.string.car_settings_category_storage, R.drawable.ic_settings_storage),
    ACCOUNT(R.id.settings_category_account, R.string.car_settings_category_account, R.drawable.ic_settings_account),
    ABOUT(R.id.settings_category_about, R.string.car_settings_category_about, R.drawable.ic_browse_info),
}

/**
 * The version as Settings' About shows it.
 *
 * A version number is not prose: "0.99.5 (12)" reads the same in every
 * locale, so a format resource would move the string without giving anything
 * to translate.
 */
internal val versionLabel = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
