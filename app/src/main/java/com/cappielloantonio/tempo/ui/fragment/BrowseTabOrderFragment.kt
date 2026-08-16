package com.cappielloantonio.tempo.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.service.BrowseTreeInvalidator
import com.cappielloantonio.tempo.util.BrowseTabOrder
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.Preferences

/**
 * Reorders the browse destinations: the first three become the car's root
 * tabs, in the order shown, and the rest become More's rows.
 *
 * Hosted by CarHostActivity rather than an activity of its own, and that is
 * load-bearing rather than convenient: that activity is deliberately not
 * marked distractionOptimized, so the platform blocks it while the car is
 * moving -- which is what keeps a drag gesture off a moving vehicle. See
 * docs/decisions/2026-08-14-customizable-browse-tabs-design.md.
 */
class BrowseTabOrderFragment : Fragment() {

    private var order: MutableList<String> = mutableListOf()

    // Retained rather than left anonymous at the attachToRecyclerView call
    // site: the adapter needs it too, to hand a touched handle's ViewHolder
    // to startDrag.
    private lateinit var itemTouchHelper: ItemTouchHelper

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

        /**
         * Moves one row and writes the result.
         *
         * Split out of the ItemTouchHelper callback so the list operation can
         * be tested without a RecyclerView -- the callback below does nothing
         * but delegate here and tell the adapter.
         *
         * Persists on every swap rather than on the way out: `onMove` fires
         * once per adjacent swap during the drag, not once at the drop, so a
         * force-quit mid-drag still cannot lose the change.
         */
        fun moveAndPersist(order: MutableList<String>, from: Int, to: Int) {
            order.add(to, order.removeAt(from))
            Preferences.setBrowseTabOrder(order)
        }

        /**
         * Guards [moveAndPersist] against [RecyclerView.NO_POSITION] (-1) and
         * reports whether the move happened.
         *
         * ItemTouchHelper.moveIfNecessary does not check for NO_POSITION
         * itself before calling onMove, so a -1 can still reach here; without
         * this, order.removeAt(-1) would throw and crash the settings screen.
         * Split out for the same reason moveAndPersist is: so the guard can
         * be exercised without a RecyclerView.
         */
        fun moveIfValid(order: MutableList<String>, from: Int, to: Int): Boolean {
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            moveAndPersist(order, from, to)
            return true
        }
    }

    // BrowseTreeInvalidator's calls in the back callback below are the only
    // media3-unstable surface this fragment touches, matching the pattern
    // PlexSignInFragment.render() uses for the same call.
    @OptIn(UnstableApi::class)
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

        // Change animations off, and this is load-bearing rather than cosmetic.
        // With them on, SimpleItemAnimator.canReuseUpdatedViewHolder() is false,
        // so the notifyItemRangeChanged below routes the *dragged* holder into
        // changed-scrap, binds a replacement for its position and cross-fades.
        // When that animation ends the old view is detached, and
        // ItemTouchHelper.onChildViewDetachedFromWindow sees it is the selected
        // holder and ends the drag -- the row stops following the finger mid
        // gesture, one swap in. Off, the badge update rebinds in place instead.
        // DefaultItemAnimator does override the payload-aware canReuseUpdatedViewHolder,
        // so a non-empty payload would work as an alternative; disabling animations
        // was chosen instead because it requires only one line at construction rather
        // than threading a payload through every notify call and bind.
        (list.itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false

        // ItemTouchHelper rather than hand-rolled touch handling, and
        // auto-scroll is the reason: five or six rows against roughly four
        // visible means a drag *must* scroll, so it is a certainty here rather
        // than an edge case. Long-press to start on the row body, which is
        // its default -- a drag that began on touch-down would fight the
        // list's own scroll there. The handle is the one place that
        // trade-off doesn't apply: it isn't a scroll target, so its
        // OnTouchListener below calls startDrag on ACTION_DOWN and skips the
        // hold. isLongPressDragEnabled stays true regardless, because the
        // handle is a small target on a head unit and long-press-anywhere is
        // the forgiving fallback, not a redundant path.
        itemTouchHelper = ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN,
                0
            ) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    val from = viewHolder.absoluteAdapterPosition
                    val to = target.absoluteAdapterPosition
                    // ItemTouchHelper.moveIfNecessary does not guard against
                    // NO_POSITION itself before calling onMove; a -1 here
                    // would otherwise reach order.removeAt(-1) and crash.
                    if (!moveIfValid(order, from, to)) return false
                    recyclerView.adapter?.notifyItemMoved(from, to)
                    // Every row's More-or-tab label may have changed, and
                    // notifyItemMoved alone does not rebind the rows that
                    // merely shifted.
                    recyclerView.post {
                        recyclerView.adapter?.notifyItemRangeChanged(0, order.size)
                    }
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

                // Removal is not offered: a destination is always somewhere,
                // above the line or below it.
                override fun isItemViewSwipeEnabled(): Boolean = false
            }
        )
        itemTouchHelper.attachToRecyclerView(list)

        // On the way out rather than per drop: one invalidation instead of one
        // per drag, and the browse UI is not foreground during the drag anyway.
        //
        // Both calls, and the second is not optional -- measured on the
        // emulator, invalidateRoot() alone redraws the tab bar while More goes
        // on serving a cached list missing the demoted destination.
        //
        // Not invalidateTree(): it also invalidates Playlists, Artists and
        // Albums, forcing re-fetches of large lists whose contents did not
        // change. A reordered destination always moves between root and
        // More, so these two cover every case.
        //
        // In a back callback rather than onDestroyView, which would also fire
        // on a configuration change and invalidate for nothing.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    BrowseTreeInvalidator.invalidateRoot()
                    BrowseTreeInvalidator.invalidateNode(Constants.MORE_ID, 0)
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        )

        return root
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Row>() {

        inner class Row(view: View) : RecyclerView.ViewHolder(view) {
            val label: TextView = view.findViewById(R.id.tab_order_label)
            val position: TextView = view.findViewById(R.id.tab_order_position)
            val handle: ImageView = view.findViewById(R.id.tab_order_handle)
        }

        override fun getItemCount(): Int = order.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
            val row = Row(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_browse_tab_order, parent, false)
            )
            // Touching the handle starts the drag immediately, with no hold
            // -- the handle is not a scroll target, so touch-down is
            // unambiguous there the way it would not be on the row body.
            // performClick() on release is what satisfies
            // ClickableViewAccessibility here instead of suppressing it: the
            // handle has no OnClickListener of its own, so this fires only
            // the click accessibility event, but that event is what tells a
            // screen reader a gesture completed on this view. It is reached
            // only on a plain tap-and-release -- once startDrag() hands the
            // gesture to ItemTouchHelper, the RecyclerView intercepts the
            // following events and this listener sees ACTION_CANCEL instead,
            // so it never fires mid-drag. Returning false regardless leaves
            // the touch otherwise unconsumed, so the row's long-press
            // fallback (enabled above) is unaffected.
            row.handle.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> itemTouchHelper.startDrag(row)
                    MotionEvent.ACTION_UP -> view.performClick()
                }
                false
            }
            return row
        }

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
