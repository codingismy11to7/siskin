package com.cappielloantonio.tempo.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.BuildConfig
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.databinding.FragmentCarSettingsBinding
import com.cappielloantonio.tempo.interfaces.LoginHost
import com.cappielloantonio.tempo.plex.api.server.ServerAddressBook
import com.cappielloantonio.tempo.service.BrowseTreeInvalidator
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.Preferences
import com.cappielloantonio.tempo.viewmodel.PlexSignInViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * What the car shows once there is a session: the toggles, the route to the
 * tab-order screen, Sign out, and the version line that opens the address panel.
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
    private var addressDialog: AlertDialog? = null
    private lateinit var viewModel: PlexSignInViewModel

    @OptIn(UnstableApi::class)
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
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
            Preferences.isContinuousPlayEnabled()
        ) { Preferences.setContinuousPlayEnabled(it) }

        addToggle(
            rows,
            getString(R.string.car_settings_replay_gain),
            Preferences.isReplayGainEnabled()
        ) { Preferences.setReplayGainEnabled(it) }

        // Invalidates the Artists tab as well as writing the key. The car
        // caches a browse list and does not re-fetch it on back-navigation, so
        // without this the tab keeps whichever shape it was first loaded with
        // and the row reads as doing nothing until the next cold start.
        addToggle(
            rows,
            getString(R.string.car_settings_artists_by_initial),
            Preferences.isArtistsByInitialEnabled()
        ) {
            Preferences.setArtistsByInitialEnabled(it)
            BrowseTreeInvalidator.invalidateNode(Constants.ARTISTS_ID, 0)
        }

        // A destination rather than a toggle, so it takes addChoice. Below the
        // toggles and above Sign out: it is a setting, and a destructive
        // terminal action still belongs last.
        addChoice(rows, getString(R.string.car_settings_customize_tabs)) {
            parentFragmentManager.beginTransaction()
                .replace(R.id.car_host_container, BrowseTabOrderFragment())
                .addToBackStack(null)
                .commit()
        }

        addChoice(rows, getString(R.string.car_settings_sign_out)) {
            viewModel.signOut()
            (requireActivity() as LoginHost).onSignedOut()
        }

        bind.versionText.text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        // The version line is the debug panel's entry point. Settings itself
        // holds only rows that change something, and debug information
        // accumulates -- see the 2026-08-14 design.
        bind.versionText.setOnClickListener { showAddressPanel() }
        // setOnClickListener makes a view clickable but not focusable, and a
        // rotary controller stops only on focusable views -- addToggle's row
        // documents the same hazard, because a TextView starts out exactly as
        // unfocusable as a bare LinearLayout does. Without this a rotary-only
        // head unit skips the version line on its way from the toggles to Sign
        // out, and the panel becomes unreachable, not merely undiscoverable.
        bind.versionText.isFocusable = true
        applyPressFeedback(bind.versionText)

        return bind.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Dismiss the address panel if it is open. CarHostActivity recreates on
        // uiMode changes, and a dialog left open would remain bound to the
        // destroyed Activity's token, leaking its window.
        addressDialog?.dismiss()
        addressDialog = null
        bind = null
    }

    /**
     * Diagnostics, reached by tapping the version line. Reports the addresses
     * known for the current server and offers a re-probe; it cannot change
     * which server or library is in use, only which address reaches this one.
     *
     * A dialog rather than a screen of its own: a debug panel is not a step of
     * anything. Readable at a standstill only, like everything else here --
     * [CarHostActivity] carries no distractionOptimized meta-data, so AAOS
     * blocks it while the car is moving.
     */
    private fun showAddressPanel(outcome: String? = null) {
        val known = ServerAddressBook.shared.knownAddresses()
        val body = buildAddressPanelBody(
            known = known,
            outcome = outcome,
            noneLabel = getString(R.string.debug_addresses_none),
            inUseLabel = getString(R.string.debug_addresses_in_use),
            directLabel = getString(R.string.debug_addresses_direct),
            relayLabel = getString(R.string.debug_addresses_relay)
        )

        // Dismiss any previously-shown dialog to prevent orphaning it when
        // showAddressPanel is called again before the prior dialog closes --
        // e.g., if re-probe is still in flight and the user taps the version
        // line again. The new dialog takes its place in addressDialog.
        addressDialog?.dismiss()

        addressDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.debug_addresses_title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.debug_addresses_reprobe, null)
            .show()

        // AlertDialog's own contract for setNeutralButton is dismiss-then-run,
        // and that is wrong for this button specifically: the worst-case race
        // (two ~6s probe timeouts, up to 10s asking plex.tv, two more against
        // the refreshed list) would leave Settings blank for tens of seconds,
        // reading as a crash rather than a button working. Replacing the
        // button View's own click listener after show() -- rather than the
        // listener passed to setNeutralButton above, which only the builder's
        // dismiss-then-run wrapper would have called -- keeps the dialog on
        // screen so reprobeAndReopen can show progress on it directly.
        addressDialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            reprobeAndReopen()
        }
    }

    /**
     * Re-races the known addresses and reopens the panel with what happened.
     *
     * The outcome has to be reported: when the same address wins again the
     * adopt is a no-op and the list redraws identically, so a silent button
     * reads as broken exactly when it is working.
     *
     * force = true because the cooldown is aimed at automatic callers -- an
     * offline car paying a full race per browse tab -- and a parked human
     * pressing this once is neither, and is most likely to press it while the
     * cooldown is armed.
     */
    private fun reprobeAndReopen() {
        val dialog = addressDialog
        val before = ServerAddressBook.shared.current()
        if (before == null) {
            // The session cleared out from under an open panel -- sign-out is
            // reachable from this same screen. A bare return here would leave
            // the dialog exactly as it was, with no way to tell that pressing
            // the button did anything at all; reopening with the same wording
            // reprobe() itself uses for "nothing answered" at least reports
            // that the press was seen.
            showAddressPanel(getString(R.string.debug_reprobe_failed))
            return
        }

        // Disabling the button and swapping the message is the visible half
        // of keeping the dialog up for the whole race -- see showAddressPanel
        // for why the button no longer auto-dismisses.
        dialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = false
        dialog?.setMessage(getString(R.string.debug_reprobe_running))

        viewLifecycleOwner.lifecycleScope.launch {
            val after = ServerAddressBook.shared.reprobe(before, force = true)
            val outcome = when (after) {
                null -> getString(R.string.debug_reprobe_failed)
                before -> getString(R.string.debug_reprobe_unchanged, after)
                else -> getString(R.string.debug_reprobe_moved, after)
            }
            showAddressPanel(outcome)
        }
    }

    companion object {
        /**
         * Builds the address panel's body text out of already-resolved data --
         * no [android.content.Context], no resource lookup, no Android framework
         * class at all. That is what lets [CarSettingsAddressPanelBodyTest]
         * assert on it directly: `unitTests.returnDefaultValues = true` stubs
         * `android.jar`, so a test that only touches framework classes can pass
         * while asserting nothing, and keeping this function framework-free is
         * what keeps that failure mode out of reach here.
         *
         * [outcome], when present, is the previous re-probe's result and is
         * shown as its own paragraph ahead of the address list.
         */
        @VisibleForTesting
        internal fun buildAddressPanelBody(
            known: ServerAddressBook.KnownAddresses,
            outcome: String?,
            noneLabel: String,
            inUseLabel: String,
            directLabel: String,
            relayLabel: String
        ): String = buildString {
            if (outcome != null) append(outcome).append("\n\n")

            fun appendAddress(uri: String) {
                append(uri)
                if (uri == known.current) append("  <- ").append(inUseLabel)
                append("\n")
            }

            fun appendGroup(label: String, addresses: List<String>) {
                if (addresses.isEmpty()) return
                append(label).append("\n")
                addresses.forEach(::appendAddress)
            }

            // known.current is live for a session written before the address
            // book existed (see knownAddresses' own KDoc), which has no
            // direct/relay candidates at all -- so "no addresses stored" has
            // to be gated on current too, or it prints directly above the one
            // address actually in use.
            if (known.direct.isEmpty() && known.relay.isEmpty() && known.current == null) {
                append(noneLabel)
            } else {
                // Direct and relay are kept apart rather than concatenated: a
                // relay URI and a direct-but-remote one are both
                // *.plex.direct-shaped and differ only by port, so flattening
                // them throws away the one distinction this panel exists to
                // show -- LAN, or out to the internet and back.
                appendGroup(directLabel, known.direct)
                appendGroup(relayLabel, known.relay)

                // Normally current is one of the candidates just printed above.
                // It is not for that same pre-address-book session, and
                // showing it separately beats a panel that omits the one
                // address actually in use.
                known.current?.takeIf { it !in known.direct && it !in known.relay }?.let {
                    append("\n")
                    appendAddress(it)
                }
            }
        }
    }
}
