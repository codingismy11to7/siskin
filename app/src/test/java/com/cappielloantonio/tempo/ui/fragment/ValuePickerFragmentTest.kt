package com.cappielloantonio.tempo.ui.fragment

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.ui.activity.CarHostActivity
import com.cappielloantonio.tempo.util.Preferences
import com.google.android.material.radiobutton.MaterialRadioButton
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Hosted in CarHostActivity as Settings pushes it, so "returns to Settings" is a back-stack fact. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class ValuePickerFragmentTest {
    @Before
    fun setUp() {
        // Robolectric keeps App's SharedPreferences in a static field between methods.
        App
            .getInstance()
            .preferences
            .edit()
            .remove("streaming_cache_size")
            .remove("precache_tracks_count")
            .commit()
        CarHostTestFixture.arrange()
    }

    @After
    fun tearDown() {
        CarHostTestFixture.tearDown()
    }

    private fun openPicker(picker: ValuePicker): Pair<CarHostActivity, View> {
        val activity = Robolectric.buildActivity(CarHostActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        activity.supportFragmentManager
            .beginTransaction()
            .replace(R.id.car_host_container, ValuePickerFragment.newInstance(picker))
            .addToBackStack(null)
            .commit()
        shadowOf(Looper.getMainLooper()).idle()
        return activity to activity.findViewById(R.id.car_host_container)
    }

    private fun tapRow(
        root: View,
        label: String,
    ) {
        val text = findText(root, label) ?: error("no row labelled $label")
        (text.parent as View).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun findText(
        view: View,
        text: String,
    ): TextView? = findTextMatching(view) { it == text }

    private fun findTextStartingWith(
        view: View,
        prefix: String,
    ): TextView? = findTextMatching(view) { it.startsWith(prefix) }

    private fun findTextMatching(
        view: View,
        matches: (String) -> Boolean,
    ): TextView? =
        when (view) {
            is TextView -> view.takeIf { matches(it.text.toString()) }
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findTextMatching(view.getChildAt(it), matches) }
            else -> null
        }

    @Test
    fun choosingASizeWritesItAndReturnsToSettings() {
        val (activity, root) = openPicker(ValuePicker.CACHE_SIZE)
        tapRow(root, "512 MB")

        assertEquals(512L, Preferences.getStreamingCacheSizeOverrideMegabytes())
        assertEquals(0, activity.supportFragmentManager.backStackEntryCount)
    }

    @Test
    fun choosingAutomaticClearsTheKey() {
        Preferences.setStreamingCacheSizeOverrideMegabytes(512L)
        val (_, root) = openPicker(ValuePicker.CACHE_SIZE)
        val automatic = findTextStartingWith(root, "Automatic (") ?: error("no Automatic row")
        (automatic.parent as View).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(Preferences.getStreamingCacheSizeOverrideMegabytes())
    }

    @Test
    fun choosingOffWritesZero() {
        val (_, root) = openPicker(ValuePicker.CACHE_SIZE)
        tapRow(root, "Off (also stops precaching)")

        assertEquals(0L, Preferences.getStreamingCacheSizeOverrideMegabytes())
    }

    @Test
    fun theStoredPrecacheCountIsTheOneMarked() {
        Preferences.setPrecacheTracksCount(5)
        val (_, root) = openPicker(ValuePicker.PRECACHE)

        val row = findText(root, "5 tracks")!!.parent as ViewGroup
        assertTrue((row.getChildAt(1) as MaterialRadioButton).isChecked)
    }

    @Test
    fun choosingACountWritesIt() {
        val (_, root) = openPicker(ValuePicker.PRECACHE)
        tapRow(root, "10 tracks")

        assertEquals(10, Preferences.getPrecacheTracksCount())
    }
}
