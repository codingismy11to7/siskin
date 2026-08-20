package com.cappielloantonio.tempo.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.BuildConfig
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.databinding.FragmentCarSettingsBinding
import com.cappielloantonio.tempo.interfaces.LoginHost
import com.cappielloantonio.tempo.service.BrowseTreeInvalidator
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.Preferences
import com.cappielloantonio.tempo.viewmodel.PlexSignInViewModel

/**
 * What the car shows once there is a session: the toggles, the route to the
 * tab-order screen, Sign out, and the version line that opens the debug screen.
 *
 * Reached by state, not by a caller. [CarHostActivity] routes
 * [com.cappielloantonio.tempo.plex.auth.PlexSignInState.Connected] here and
 * everything else to [PlexSignInFragment] -- so signing out is not a navigation
 * this fragment performs, it is a state change that moves the activity off this
 * screen. See the activity's KDoc for why the routing lives there.
 *
 * Builds its rows once, in [onCreateView], rather than observing state: the
 * only state this screen is ever shown for is Connected, and the one transition
 * out of it is the one that replaces this fragment. Preferences are therefore
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

        // A destination rather than a toggle, so it takes addChoice. Below the
        // toggles and above Sign out: it is a setting, and a destructive
        // terminal action still belongs last.
        addChoice(rows, getString(R.string.car_settings_customize_tabs)) {
            parentFragmentManager
                .beginTransaction()
                .replace(R.id.car_host_container, BrowseTabOrderFragment())
                .addToBackStack(null)
                .commit()
        }

        addChoice(rows, getString(R.string.car_settings_sign_out)) {
            viewModel.signOut()
            (requireActivity() as LoginHost).onSignedOut()
        }

        bind.versionText.text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
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

        return bind.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bind = null
    }
}
