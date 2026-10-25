package com.cappielloantonio.tempo.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
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
    /**
     * Kept on the fragment rather than read back from the rail: the rail is a
     * new view each time this screen returns from Choose server, and the
     * fragment instance is what survives that trip.
     */
    private var selected = DebugTab.entries.first()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        savedInstanceState?.getString(SELECTED)?.let { selected = DebugTab.valueOf(it) }

        val bind = FragmentCarDebugBinding.inflate(inflater, container, false)
        val rail = bind.debugRail
        DebugTab.entries.forEach { tab ->
            rail.menu.add(Menu.NONE, tab.ordinal, tab.ordinal, tab.label).setIcon(tab.icon)
        }
        rail.selectedItemId = selected.ordinal
        rail.setOnItemSelectedListener { item ->
            show(DebugTab.entries[item.itemId])
            true
        }
        show(selected)
        return bind.root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(SELECTED, selected.name)
    }

    /**
     * Shows [tab], adding its fragment the first time. Hidden rather than
     * replaced, because a re-probe runs in the Server tab's view lifecycle
     * for up to tens of seconds and replacing the tab would cancel it.
     *
     * Never on the back stack: tabs are not history, so one back press leaves
     * the screen from any of them.
     */
    private fun show(tab: DebugTab) {
        selected = tab
        val children = childFragmentManager
        val transaction = children.beginTransaction().setReorderingAllowed(true)
        DebugTab.entries.forEach { other ->
            val fragment = children.findFragmentByTag(other.name)
            if (other == tab) {
                if (fragment == null) {
                    transaction.add(R.id.debug_tab_container, tab.create(), tab.name)
                } else {
                    transaction.show(fragment)
                }
            } else if (fragment != null) {
                transaction.hide(fragment)
            }
        }
        transaction.commit()
    }

    private companion object {
        const val SELECTED = "selected"
    }
}
