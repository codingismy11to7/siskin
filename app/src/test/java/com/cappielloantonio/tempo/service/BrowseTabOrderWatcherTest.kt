package com.cappielloantonio.tempo.service

import android.os.Looper
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.util.BrowseTabOrder
import com.cappielloantonio.tempo.util.BrowseTabOrderFixture
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.Preferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class BrowseTabOrderWatcherTest {
    private var calls = 0
    private val watcher = BrowseTabOrderWatcher { calls++ }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before
    fun setUp() {
        BrowseTabOrderFixture.clearSavedOrder()
        shadowOf(Looper.getMainLooper()).idle()
        watcher.start()
    }

    @After
    fun tearDown() {
        watcher.stop()
    }

    @Test
    fun `writes within the window produce one call once they settle`() {
        Preferences.setBrowseTabOrder(listOf(Constants.ALBUMS_ID, Constants.PLAYLIST_ID))
        advance(BrowseTabOrderWatcher.DEBOUNCE_MS - 100)
        Preferences.setBrowseTabOrder(listOf(Constants.DECADES_ID, Constants.ALBUMS_ID))
        advance(BrowseTabOrderWatcher.DEBOUNCE_MS - 100)
        assertEquals(0, calls)

        advance(BrowseTabOrderWatcher.DEBOUNCE_MS)
        assertEquals(1, calls)
    }

    @Test
    fun `a write that resolves to the same order produces no call`() {
        Preferences.setBrowseTabOrder(BrowseTabOrder.DEFAULT_ORDER)
        advance(BrowseTabOrderWatcher.DEBOUNCE_MS * 2)
        assertEquals(0, calls)
    }

    @Test
    fun `writes that end where they started produce no call`() {
        Preferences.setBrowseTabOrder(listOf(Constants.ALBUMS_ID))
        advance(BrowseTabOrderWatcher.DEBOUNCE_MS - 100)
        Preferences.setBrowseTabOrder(BrowseTabOrder.DEFAULT_ORDER)
        advance(BrowseTabOrderWatcher.DEBOUNCE_MS * 2)
        assertEquals(0, calls)
    }

    @Test
    fun `a second change after the first settles is reported again`() {
        Preferences.setBrowseTabOrder(listOf(Constants.ALBUMS_ID))
        advance(BrowseTabOrderWatcher.DEBOUNCE_MS * 2)
        Preferences.setBrowseTabOrder(listOf(Constants.DECADES_ID))
        advance(BrowseTabOrderWatcher.DEBOUNCE_MS * 2)
        assertEquals(2, calls)
    }

    @Test
    fun `stop cancels a scheduled check and ignores later writes`() {
        Preferences.setBrowseTabOrder(listOf(Constants.ALBUMS_ID))
        advance(BrowseTabOrderWatcher.DEBOUNCE_MS - 100)
        watcher.stop()
        advance(BrowseTabOrderWatcher.DEBOUNCE_MS * 2)
        Preferences.setBrowseTabOrder(listOf(Constants.DECADES_ID))
        advance(BrowseTabOrderWatcher.DEBOUNCE_MS * 2)
        assertEquals(0, calls)
    }

    @Test
    fun `clearing every preference is reported`() {
        Preferences.setBrowseTabOrder(listOf(Constants.ALBUMS_ID))
        advance(BrowseTabOrderWatcher.DEBOUNCE_MS * 2)
        App
            .getInstance()
            .preferences
            .edit()
            .clear()
            .commit()
        advance(BrowseTabOrderWatcher.DEBOUNCE_MS * 2)
        assertEquals(2, calls)
    }

    @Test
    fun `a change to another key produces no call`() {
        App
            .getInstance()
            .preferences
            .edit()
            .putBoolean("unrelated_key", true)
            .commit()
        advance(BrowseTabOrderWatcher.DEBOUNCE_MS * 2)
        assertEquals(0, calls)
    }
}
