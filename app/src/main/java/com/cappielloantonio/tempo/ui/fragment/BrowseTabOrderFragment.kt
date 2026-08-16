package com.cappielloantonio.tempo.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.util.BrowseTabOrder
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.Preferences

/**
 * Reorders the browse destinations: the first three become the car's root
 * tabs, in the order shown, and the rest become More's rows.
 *
 * Hosted by CarSignInActivity rather than an activity of its own, and that is
 * load-bearing rather than convenient: that activity is deliberately not
 * marked distractionOptimized, so the platform blocks it while the car is
 * moving -- which is what keeps a drag gesture off a moving vehicle. See
 * docs/decisions/2026-08-14-customizable-browse-tabs-design.md.
 */
class BrowseTabOrderFragment : Fragment() {

    private var order: MutableList<String> = mutableListOf()

    companion object {
        /**
         * The label for a destination row, reusing the string its browse tab
         * already uses so the two can never disagree. 0 for an unknown id --
         * [BrowseTabOrder.resolve] filters those out before they reach here,
         * so this is a guard rather than a path.
         */
        @StringRes
        fun labelFor(id: String): Int = when (id) {
            Constants.PLAYLIST_ID -> R.string.browse_playlists
            Constants.ARTISTS_ID -> R.string.browse_artists
            Constants.ALBUMS_ID -> R.string.browse_albums
            Constants.DISCOVER_ID -> R.string.browse_discover
            Constants.DECADES_ID -> R.string.browse_decades
            else -> 0
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_browse_tab_order, container, false)

        // The resolved order, not the raw saved one: a first-run user has
        // nothing saved and would otherwise be handed an empty list.
        order = BrowseTabOrder.resolve(Preferences.getBrowseTabOrder()).toMutableList()

        val list = root.findViewById<RecyclerView>(R.id.tab_order_list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = Adapter()

        return root
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Row>() {

        inner class Row(view: View) : RecyclerView.ViewHolder(view) {
            val label: TextView = view.findViewById(R.id.tab_order_label)
            val position: TextView = view.findViewById(R.id.tab_order_position)
            val handle: ImageView = view.findViewById(R.id.tab_order_handle)
        }

        override fun getItemCount(): Int = order.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row = Row(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_browse_tab_order, parent, false)
        )

        override fun onBindViewHolder(holder: Row, position: Int) {
            val id = order[position]
            holder.label.setText(labelFor(id))
            // Which side of the line a row is on, said rather than implied by
            // a divider the drag would have to keep re-positioning.
            holder.position.setText(
                if (position < BrowseTabOrder.ROOT_TAB_COUNT) R.string.car_tab_order_tab_badge
                else R.string.browse_more
            )
            holder.handle.contentDescription =
                getString(R.string.car_tab_order_drag_handle)
        }
    }
}
