package com.cappielloantonio.tempo.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.databinding.FragmentCarDebugBinding

/**
 * Diagnostics, reached by tapping the version line in Settings: a rail of tabs
 * down the start edge, one fragment per tab beside it. See
 * docs/decisions/2026-10-25-debug-tabs-design.md.
 *
 * Readable at a standstill only: [com.cappielloantonio.tempo.ui.activity.CarHostActivity]
 * carries no distractionOptimized meta-data, so AAOS blocks it while the car is
 * moving. That is what lets this screen be as dense as it needs to be.
 */
class CarDebugFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val bind = FragmentCarDebugBinding.inflate(inflater, container, false)
        if (childFragmentManager.findFragmentById(R.id.debug_tab_container) == null) {
            childFragmentManager
                .beginTransaction()
                .add(R.id.debug_tab_container, DebugServerFragment())
                .commit()
        }
        return bind.root
    }
}
