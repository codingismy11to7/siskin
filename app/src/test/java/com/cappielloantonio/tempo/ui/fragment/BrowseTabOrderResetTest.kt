package com.cappielloantonio.tempo.ui.fragment

import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.ui.activity.CarHostActivity
import com.cappielloantonio.tempo.util.BrowseTabOrder
import com.cappielloantonio.tempo.util.BrowseTabOrderFixture
import com.cappielloantonio.tempo.util.Preferences
import com.cappielloantonio.tempo.util.StreamingCacheTestHooks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The button itself, through the screen: BrowseTabOrderMoveTest covers
 * resetAndPersist, and this covers that a tap reaches it and the rows follow.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class BrowseTabOrderResetTest {
    private val custom = BrowseTabOrder.DEFAULT_ORDER.reversed()

    @Before
    fun setUp() {
        BrowseTabOrderFixture.clearSavedOrder()
        CarHostTestFixture.arrange()
        Preferences.setBrowseTabOrder(custom)
    }

    @After
    fun tearDown() {
        StreamingCacheTestHooks.release()
        CarHostTestFixture.tearDown()
    }

    private fun openTabOrder(): CarHostActivity {
        val activity = Robolectric.buildActivity(CarHostActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        activity.supportFragmentManager
            .beginTransaction()
            .replace(R.id.car_host_container, BrowseTabOrderFragment())
            .addToBackStack(null)
            .commit()
        shadowOf(Looper.getMainLooper()).idle()
        return activity
    }

    private fun CarHostActivity.list(): RecyclerView = findViewById(R.id.tab_order_list)

    private fun CarHostActivity.tapReset() {
        findViewById<View>(R.id.tab_order_reset).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The labels of the rows laid out, top first -- not every row fits on screen. */
    private fun CarHostActivity.shownLabels(): List<String> {
        val list = list()
        return (0 until list.childCount).map {
            list
                .getChildAt(it)
                .findViewById<TextView>(R.id.tab_order_label)
                .text
                .toString()
        }
    }

    private fun labelsOf(
        activity: CarHostActivity,
        ids: List<String>,
    ) = ids.map { activity.getString(BrowseTabOrderFragment.labelFor(it)) }

    @Test
    fun tappingResetShowsTheDefaultOrderAndForgetsTheSavedOne() {
        val activity = openTabOrder()
        assertEquals(labelsOf(activity, custom).take(activity.shownLabels().size), activity.shownLabels())

        activity.tapReset()

        val shown = activity.shownLabels()
        assertTrue("too few rows laid out to mean anything", shown.size >= BrowseTabOrder.ROOT_TAB_COUNT)
        assertEquals(labelsOf(activity, BrowseTabOrder.DEFAULT_ORDER).take(shown.size), shown)
        assertEquals(emptyList<String>(), Preferences.getBrowseTabOrder())
    }

    @Test
    fun resetIsIgnoredWhileARowIsBeingDragged() {
        val activity = openTabOrder()
        val list = activity.list()
        // ItemTouchHelper attaches as the list's only item decoration.
        val touchHelper = list.getItemDecorationAt(0) as ItemTouchHelper
        touchHelper.startDrag(list.findViewHolderForAdapterPosition(0)!!)

        activity.tapReset()

        assertEquals(custom, Preferences.getBrowseTabOrder())
    }
}
