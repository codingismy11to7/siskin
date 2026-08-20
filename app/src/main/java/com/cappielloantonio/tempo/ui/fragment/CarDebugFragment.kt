package com.cappielloantonio.tempo.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.databinding.FragmentCarDebugBinding
import com.cappielloantonio.tempo.plex.api.server.ServerAddressBook
import com.cappielloantonio.tempo.viewmodel.PlexSignInViewModel
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * Diagnostics, reached by tapping the version line in Settings.
 *
 * A screen rather than the dialog it used to be. The 2026-08-14 design that
 * introduced the panel said an activity was "pre-building for a panel that does
 * not exist yet" and that the promotion belonged at the moment the dialog was
 * outgrown; a second action is that moment. AlertDialog has three button slots
 * and OK and Re-probe held two, and its buttons are phone-sized on a screen
 * where every other control is 72dp because the taps happen at arm's length.
 *
 * Reports the addresses known for the current server and offers a re-probe; it
 * cannot change which server or library is in use, only which address reaches
 * this one. Choosing a server is a separate row, and it goes through the
 * sign-in flow's own picker rather than doing anything itself.
 *
 * Readable at a standstill only: [com.cappielloantonio.tempo.ui.activity.CarHostActivity]
 * carries no distractionOptimized meta-data, so AAOS blocks it while the car is
 * moving. That is what lets this screen be as dense as it needs to be.
 */
class CarDebugFragment : Fragment() {
    private var bind: FragmentCarDebugBinding? = null

    /**
     * Held because [reprobe] disables it for the duration of a race. A local
     * would not do: the lambda that calls [reprobe] is built as an argument to
     * the call that creates the button, so there is nothing to close over yet
     * at that point, and a local `var` cannot be `lateinit`.
     */
    private var reprobeRow: MaterialButton? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val bind = FragmentCarDebugBinding.inflate(inflater, container, false)
        this.bind = bind

        renderAddresses(outcome = null)

        reprobeRow =
            addChoice(
                bind.choiceContainer,
                getString(R.string.debug_addresses_reprobe),
            ) {
                reprobe()
            }

        addChoice(bind.choiceContainer, getString(R.string.debug_choose_server)) {
            chooseServer()
        }

        return bind.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bind = null
        reprobeRow = null
    }

    /**
     * The address report, static by design: it reads what is already persisted
     * and probes nothing on open. Per-address probing on open is strictly more
     * informative and was rejected in the 2026-08-14 design because the
     * re-probe row covers the same ground with code that already exists.
     *
     * [outcome], when present, is the previous re-probe's result.
     */
    private fun renderAddresses(outcome: String?) {
        val bind = this.bind ?: return
        bind.addressText.text =
            buildAddressPanelBody(
                known = ServerAddressBook.shared.knownAddresses(),
                outcome = outcome,
                noneLabel = getString(R.string.debug_addresses_none),
                inUseLabel = getString(R.string.debug_addresses_in_use),
                directLabel = getString(R.string.debug_addresses_direct),
                relayLabel = getString(R.string.debug_addresses_relay),
            )
    }

    /**
     * Re-races the known addresses and redraws with what happened.
     *
     * The outcome has to be reported: when the same address wins again the
     * adopt is a no-op and the list redraws identically, so a silent row reads
     * as broken exactly when it is working.
     *
     * force = true because the cooldown is aimed at automatic callers -- an
     * offline car paying a full race per browse tab -- and a parked human
     * pressing this once is neither, and is most likely to press it while the
     * cooldown is armed.
     *
     * On a screen rather than in a dialog, this is just a disabled row and a
     * changed string. The dialog version had to replace the neutral button's
     * click listener after show(), because AlertDialog's dismiss-then-run
     * contract would have torn the panel down during a race that can take tens
     * of seconds. Nothing here dismisses, so none of that survives the move.
     */
    private fun reprobe() {
        val before = ServerAddressBook.shared.current()
        if (before == null) {
            // The session cleared out from under an open screen -- sign out is
            // one back press away. Reporting the same wording reprobe() uses
            // for "nothing answered" at least shows the press was seen.
            renderAddresses(getString(R.string.debug_reprobe_failed))
            return
        }

        reprobeRow?.isEnabled = false
        bind?.addressText?.text = getString(R.string.debug_reprobe_running)

        viewLifecycleOwner.lifecycleScope.launch {
            val after = ServerAddressBook.shared.reprobe(before, force = true)
            val outcome =
                when (after) {
                    null -> getString(R.string.debug_reprobe_failed)
                    before -> getString(R.string.debug_reprobe_unchanged, after)
                    else -> getString(R.string.debug_reprobe_moved, after)
                }
            renderAddresses(outcome)
            reprobeRow?.isEnabled = true
        }
    }

    /**
     * Opens the sign-in flow's own server picker, without signing in.
     *
     * **Pushed, not routed, and that is the whole design.** Back out of the
     * picker should return here, to the screen it was opened from, and a back
     * stack is precisely the thing that knows how to do that. Pushing also
     * keeps CarHostActivity's router out of the way for free: it declines to
     * act while the back stack is non-empty, and this transaction only makes it
     * emptier by nothing.
     *
     * An earlier draft had the router show the picker instead. That forced this
     * row to pop *itself* first -- destroying the very entry that would have
     * brought the user back -- and then to name a hardcoded destination for
     * back, which could only ever be a state the flow already had. Settings,
     * in practice, which is not where anyone came from.
     *
     * The order below does not matter. The push makes the back stack deeper, so
     * the router is silent either way, and the fragment picks up whatever state
     * is current when it starts observing.
     */
    private fun chooseServer() {
        parentFragmentManager
            .beginTransaction()
            .replace(R.id.car_host_container, PlexSignInFragment.pushed())
            .addToBackStack(null)
            .commit()

        ViewModelProvider(requireActivity())[PlexSignInViewModel::class.java]
            .reopenServerPicker()
    }

    companion object {
        /**
         * Builds the address report out of already-resolved data -- no
         * [android.content.Context], no resource lookup, no Android framework
         * class at all. That is what lets [CarDebugAddressPanelBodyTest] assert
         * on it directly: `unitTests.returnDefaultValues = true` stubs
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
            relayLabel: String,
        ): String =
            buildString {
                if (outcome != null) append(outcome).append("\n\n")

                fun appendAddress(uri: String) {
                    append(uri)
                    if (uri == known.current) append("  <- ").append(inUseLabel)
                    append("\n")
                }

                fun appendGroup(
                    label: String,
                    addresses: List<String>,
                ) {
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
