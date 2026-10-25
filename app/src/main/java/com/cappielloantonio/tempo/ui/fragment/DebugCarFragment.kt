package com.cappielloantonio.tempo.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import androidx.fragment.app.Fragment
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.car.VehicleInfoReader
import com.cappielloantonio.tempo.car.VehicleInfoSource
import com.cappielloantonio.tempo.databinding.FragmentDebugCarBinding
import com.cappielloantonio.tempo.plex.PlexIdentity

/** The debug screen's Car tab: what this car tells Plex it is. */
class DebugCarFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val bind = FragmentDebugCarBinding.inflate(inflater, container, false)
        bind.deviceText.text = deviceBody()
        return bind.root
    }

    /**
     * What this car tells Plex it is, and which tier answered. The tier is the
     * point: the same make and model read from the vehicle and read from
     * Build.* are indistinguishable in the Plex device row.
     */
    private fun deviceBody(): String {
        val vehicle = VehicleInfoReader.identity()
        return buildDevicePanelBody(
            headers = PlexIdentity.deviceHeaders(vehicle),
            noNameLabel = getString(R.string.debug_device_no_name),
            fromLabel = getString(R.string.debug_device_from),
            sourceLabel =
                getString(
                    when (vehicle.source) {
                        VehicleInfoSource.VEHICLE -> R.string.debug_device_source_vehicle
                        VehicleInfoSource.BUILD -> R.string.debug_device_source_build
                        VehicleInfoSource.UNKNOWN -> R.string.debug_device_source_unknown
                    },
                ),
        )
    }

    companion object {
        /**
         * Reads the headers rather than rebuilding them, so this panel cannot
         * drift from what [PlexIdentity] actually sends.
         *
         * [fromLabel] is a format string taking one argument.
         */
        @VisibleForTesting
        internal fun buildDevicePanelBody(
            headers: Map<String, String>,
            noNameLabel: String,
            fromLabel: String,
            sourceLabel: String,
        ): String =
            buildString {
                append(headers["X-Plex-Device-Name"] ?: noNameLabel).append("\n")
                append(headers["X-Plex-Device"]).append(" / ").append(headers["X-Plex-Model"]).append("\n")
                append(fromLabel.format(sourceLabel))
            }
    }
}
