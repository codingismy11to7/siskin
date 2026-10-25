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
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FragmentCarDebugBinding.inflate(inflater, container, false).root

    /**
     * In onViewCreated rather than onCreateView because [show] commits
     * immediately, and a child fragment cannot be added to a container until
     * this fragment's view exists.
     */
    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        val rail = FragmentCarDebugBinding.bind(view).debugRail
        DebugTab.entries.forEach { tab ->
            rail.menu.add(Menu.NONE, tab.id, tab.ordinal, tab.label).setIcon(tab.icon)
        }

        val current = showing() ?: DebugTab.DEFAULT
        show(current)
        rail.selectedItemId = current.id
        rail.setOnItemSelectedListener { item ->
            show(DebugTab.entries.first { it.id == item.itemId })
            true
        }
    }

    /**
     * The selected tab is whichever tab fragment is not hidden, and nothing
     * else records it. The child fragment manager persists that through a
     * uiMode flip, through Choose server covering this screen, and through
     * this fragment being recreated while covered -- the cases a second copy
     * could fall out of step in.
     */
    private fun showing(): DebugTab? =
        DebugTab.entries.firstOrNull {
            childFragmentManager.findFragmentByTag(it.name)?.isHidden == false
        }

    /**
     * Shows [tab], adding its fragment the first time. Hidden rather than
     * replaced, because a re-probe runs in the Server tab's view lifecycle
     * for up to tens of seconds and replacing the tab would cancel it.
     *
     * Committed now rather than queued: the next selection decides what to
     * hide by looking fragments up, and a queued add is not there to find, so
     * a quick second selection would leave two tabs showing.
     *
     * Never on the back stack: tabs are not history, so one back press leaves
     * the screen from any of them.
     */
    private fun show(tab: DebugTab) {
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
        transaction.commitNow()
    }
}
