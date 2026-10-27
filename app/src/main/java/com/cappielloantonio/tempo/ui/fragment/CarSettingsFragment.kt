package com.cappielloantonio.tempo.ui.fragment

import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.fragment.app.Fragment
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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the car shows once there is a session: the toggles, the value rows, the
 * route to the tab-order screen, Switch user, Sign out, and the version line
 * that opens the debug screen.
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
 * replaces this fragment. Preferences are therefore
 * read at build time -- which is also true on the way back from
 * [BrowseTabOrderFragment], since popping that back-stack entry recreates this
 * fragment's view.
 *
 * No `OnBackPressedCallback` either, deliberately.
 * [PlexSignInViewModel.handlesBackPress] reports false for Connected, so back
 * falls through to the platform default and finishes the activity -- which is
 * what it should do from the screen you land on rather than pass through.
 */
@UnstableApi
class CarSettingsFragment : Fragment() {
    private var bind: FragmentCarSettingsBinding? = null
    private lateinit var viewModel: PlexSignInViewModel

    @OptIn(UnstableApi::class)
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        viewModel = ViewModelProvider(requireActivity())[PlexSignInViewModel::class.java]
        val bind = FragmentCarSettingsBinding.inflate(inflater, container, false)
        this.bind = bind

        val rows = bind.choiceContainer

        // Before Sign out, because a destructive terminal action belongs last.
        // Each row reads its preference here rather than holding any state.
        addToggle(
            rows,
            getString(R.string.car_settings_continuous_play),
            Preferences.isContinuousPlayEnabled(),
        ) { Preferences.setContinuousPlayEnabled(it) }

        addToggle(
            rows,
            getString(R.string.car_settings_replay_gain),
            Preferences.isReplayGainEnabled(),
        ) { Preferences.setReplayGainEnabled(it) }

        // Invalidates the Artists tab as well as writing the key. The car
        // caches a browse list and does not re-fetch it on back-navigation, so
        // without this the tab keeps whichever shape it was first loaded with
        // and the row reads as doing nothing until the next cold start.
        addToggle(
            rows,
            getString(R.string.car_settings_artists_by_initial),
            Preferences.isArtistsByInitialEnabled(),
        ) {
            Preferences.setArtistsByInitialEnabled(it)
            BrowseTreeInvalidator.invalidateNode(Constants.ARTISTS_ID, 0)
        }

        addToggle(
            rows,
            getString(R.string.car_settings_artist_mix),
            Preferences.isArtistMixEnabled(),
        ) {
            Preferences.setArtistMixEnabled(it)
            BrowseTreeInvalidator.invalidateArtistPages()
        }

        addToggle(
            rows,
            getString(R.string.car_settings_artist_radio),
            Preferences.isArtistRadioEnabled(),
        ) {
            Preferences.setArtistRadioEnabled(it)
            BrowseTreeInvalidator.invalidateArtistPages()
        }

        // Values chosen from a list, so each opens a picker. Settings rebuilds
        // its view when the picker pops, which is what refreshes them.
        val context = requireContext()
        val automatic = DownloadUtil.getAutomaticStreamingCacheSizeMegabytes(context)
        val cacheChoice = StreamingCacheChoices.current(Preferences.getStreamingCacheSizeOverrideMegabytes(), automatic)
        val cacheValue =
            addValueRow(
                rows,
                getString(R.string.car_settings_cache_size),
                StreamingCacheChoices.cacheSizeSummary(resources, cacheChoice, usedBytes = null),
            ) { push(ValuePickerFragment.newInstance(ValuePicker.CACHE_SIZE)) }.value

        addValueRow(
            rows,
            getString(R.string.car_settings_precache),
            StreamingCacheChoices.precacheSummary(
                resources,
                Preferences.getPrecacheTracksCount(),
                cacheOff = cacheChoice == CacheSizeChoice.Off,
            ),
        ) { push(ValuePickerFragment.newInstance(ValuePicker.PRECACHE)) }

        // Reading usage may build the cache, which reads its index from disk.
        // It lands in a line already laid out, so it changes text, not layout.
        // Skipped when the cache is off: that line shows no usage, so building
        // the cache would load its index for nothing. A failed read leaves the
        // line without a figure rather than taking the process down.
        if (cacheChoice != CacheSizeChoice.Off) {
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

        val tabs = BrowseTabOrder.rootTabs(BrowseTabOrder.resolve(Preferences.getBrowseTabOrder()))
        addValueRow(
            rows,
            getString(R.string.car_settings_customize_tabs),
            tabs.joinToString(" · ") { getString(BrowseTabOrderFragment.labelFor(it)) },
        ) { push(BrowseTabOrderFragment()) }

        // Shown only for a Home with more than one member. Drawn from the
        // remembered answer, so the row is usually in place before plex.tv
        // replies; a failed reply then changes nothing, since hiding a drawn
        // row would slide Sign out up under the finger aimed at it. One line,
        // ellipsized, so a long name or a rename cannot change the row's height.
        val switchUser = addValueRow(rows, getString(R.string.car_settings_switch_user), "") { switchUser() }
        switchUser.value.maxLines = 1
        switchUser.value.ellipsize = TextUtils.TruncateAt.END

        fun showHome(home: HomeUsersRepository.Home?) {
            val shown = home != null && home.users.size >= 2
            if (shown) switchUser.value.text = getString(R.string.car_settings_listening_as, home.current.title.orEmpty())
            switchUser.row.visibility = if (shown) View.VISIBLE else View.GONE
        }
        showHome(HomeUsersRepository.remembered())

        addChoice(rows, getString(R.string.car_settings_sign_out)) {
            viewModel.signOut()
            (requireActivity() as LoginHost).onSignedOut()
        }

        bind.versionText.text = versionLabel
        // The version line is the debug screen's entry point. Settings itself
        // holds only rows that change something, and debug information
        // accumulates -- see the 2026-08-14 design. It is a screen rather than
        // a dialog as of the 2026-08-16 one.
        bind.versionText.setOnClickListener {
            parentFragmentManager
                .beginTransaction()
                .replace(R.id.car_host_container, CarDebugFragment())
                .addToBackStack(null)
                .commit()
        }
        // setOnClickListener makes a view clickable but not focusable, and a
        // rotary controller stops only on focusable views -- addToggle's row
        // documents the same hazard, because a TextView starts out exactly as
        // unfocusable as a bare LinearLayout does. Without this a rotary-only
        // head unit skips the version line on its way from the toggles to Sign
        // out, and the screen becomes unreachable, not merely undiscoverable.
        bind.versionText.isFocusable = true
        applyPressFeedback(bind.versionText)

        viewLifecycleOwner.lifecycleScope.launch { HomeUsersRepository.refresh()?.let(::showHome) }

        return bind.root
    }

    private fun push(fragment: Fragment) {
        parentFragmentManager
            .beginTransaction()
            .replace(R.id.car_host_container, fragment)
            .addToBackStack(null)
            .commit()
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

    override fun onDestroyView() {
        super.onDestroyView()
        bind = null
    }
}

/**
 * The version as Settings and the debug screen's About tab both show it.
 *
 * A version number is not prose: "0.99.5 (12)" reads the same in every
 * locale, so a format resource would move the string without giving anything
 * to translate.
 */
internal val versionLabel = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
