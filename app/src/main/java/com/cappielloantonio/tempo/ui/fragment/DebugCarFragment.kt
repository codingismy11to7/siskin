package com.cappielloantonio.tempo.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.fragment.app.Fragment
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.car.VehicleInfoReader
import com.cappielloantonio.tempo.car.VehicleInfoSource
import com.cappielloantonio.tempo.car.VehicleInputs
import com.cappielloantonio.tempo.databinding.FragmentDebugCarBinding
import com.cappielloantonio.tempo.databinding.ItemDebugIdentityTierBinding
import com.cappielloantonio.tempo.plex.PlexIdentity
import com.cappielloantonio.tempo.util.Preferences
import com.google.android.material.color.MaterialColors

/**
 * The debug screen's Car tab: what each identity tier would tell Plex, and
 * which one does. Tapping a tier saves it as where the ladder starts. See
 * docs/decisions/2026-10-25-vehicle-identity-tier-pick-design.md.
 */
class DebugCarFragment : Fragment() {
    private var bind: FragmentDebugCarBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val bind = FragmentDebugCarBinding.inflate(inflater, container, false)
        this.bind = bind
        bind.deviceInfo.setOnClickListener { showTooltip(it) }
        render()
        return bind.root
    }

    override fun onDestroyView() {
        bind = null
        super.onDestroyView()
    }

    /** Rebuilt whole on every pick: three cards, and each title can change. */
    private fun render() {
        val container = bind?.tierContainer ?: return
        container.removeAllViews()
        val cards =
            buildTierCards(
                inputs = VehicleInfoReader.inputs() ?: VehicleInputs.NONE,
                startAt = Preferences.getVehicleIdentityTier(),
                labels = labels(),
            )
        for (card in cards) {
            val item = ItemDebugIdentityTierBinding.inflate(layoutInflater, container, true)
            item.tierTitle.text = card.title
            item.tierBody.text = card.body
            item.tierRaw.text = card.raw
            item.root.isEnabled = card.available
            item.root.alpha = if (card.available) 1f else DISABLED_ALPHA
            if (card.available) {
                item.root.setOnClickListener {
                    Preferences.setVehicleIdentityTier(card.tier)
                    render()
                }
            }
            if (card.reported) {
                item.root.strokeColor = MaterialColors.getColor(item.root, androidx.appcompat.R.attr.colorPrimary)
                item.root.strokeWidth = resources.getDimensionPixelSize(R.dimen.debug_tier_reported_stroke)
            }
        }
    }

    /** Dismissed by any tap outside it, which is what `focusable = true` buys. */
    private fun showTooltip(anchor: View) {
        //noinspection InflateParams -- a PopupWindow's content has no parent to inflate into
        val text = layoutInflater.inflate(R.layout.debug_tooltip, null) as TextView
        text.setText(R.string.debug_device_info_message)
        PopupWindow(text, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            elevation = resources.getDimension(R.dimen.debug_tooltip_elevation)
            isOutsideTouchable = true
            showAsDropDown(anchor)
        }
    }

    private fun labels() =
        TierLabels(
            vehicle = getString(R.string.debug_tier_vehicle),
            build = getString(R.string.debug_tier_build),
            constants = getString(R.string.debug_tier_constants),
            constantsDetail = getString(R.string.debug_tier_constants_detail),
            noName = getString(R.string.debug_device_no_name),
            nothingReported = getString(R.string.debug_tier_nothing_reported),
            nothingHere = getString(R.string.debug_tier_nothing_here),
            fallbackFrom = getString(R.string.debug_tier_fallback_from),
        )

    companion object {
        private const val DISABLED_ALPHA = 0.5f

        /** [nothingHere] takes the tier's name; [fallbackFrom] takes it and the picked tier's. */
        @VisibleForTesting
        internal data class TierLabels(
            val vehicle: String,
            val build: String,
            val constants: String,
            val constantsDetail: String,
            val noName: String,
            val nothingReported: String,
            val nothingHere: String,
            val fallbackFrom: String,
        ) {
            fun nameOf(tier: VehicleInfoSource) =
                when (tier) {
                    VehicleInfoSource.VEHICLE -> vehicle
                    VehicleInfoSource.BUILD -> build
                    VehicleInfoSource.UNKNOWN -> constants
                }
        }

        @VisibleForTesting
        internal data class TierCard(
            val tier: VehicleInfoSource,
            val title: String,
            val body: String,
            val raw: String,
            val available: Boolean,
            val reported: Boolean,
        )

        /**
         * Reads each card's body from [PlexIdentity.deviceHeaders] rather than
         * rebuilding it, so the tab cannot drift from what is actually sent.
         * Framework-free so it can be asserted under `returnDefaultValues`.
         */
        @VisibleForTesting
        internal fun buildTierCards(
            inputs: VehicleInputs,
            startAt: VehicleInfoSource,
            labels: TierLabels,
        ): List<TierCard> {
            val reported = inputs.resolve(startAt).source
            return VehicleInputs.LADDER.map { tier ->
                val identity = inputs.tier(tier)
                val name = labels.nameOf(tier)
                val title =
                    when {
                        tier == reported && tier != startAt -> labels.fallbackFrom.format(name, labels.nameOf(startAt))
                        tier == startAt && tier != reported -> labels.nothingHere.format(name)
                        else -> name
                    }
                val body =
                    identity?.let { PlexIdentity.deviceHeaders(it) }?.let { headers ->
                        "${headers["X-Plex-Device-Name"] ?: labels.noName}\n${headers["X-Plex-Device"]} / ${headers["X-Plex-Model"]}"
                    } ?: labels.nothingReported
                val raw =
                    when (tier) {
                        VehicleInfoSource.VEHICLE -> {
                            "INFO_MAKE=${quoted(inputs.carMake)}  INFO_MODEL=${quoted(inputs.carModel)}  " +
                                "INFO_MODEL_YEAR=${inputs.carYear ?: ABSENT}"
                        }

                        VehicleInfoSource.BUILD -> {
                            "MANUFACTURER=${quoted(inputs.buildManufacturer)}  MODEL=${quoted(inputs.buildModel)}"
                        }

                        VehicleInfoSource.UNKNOWN -> {
                            labels.constantsDetail
                        }
                    }
                TierCard(tier, title, body, raw, available = identity != null, reported = tier == reported)
            }
        }

        private const val ABSENT = "∅"

        /** Quoted so a blank or padded answer is visible as such. */
        private fun quoted(value: String?) = value?.let { "\"$it\"" } ?: ABSENT
    }
}
