package com.cappielloantonio.tempo.ui.fragment

import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.repository.HomeUsersRepository
import com.cappielloantonio.tempo.service.BrowseTreeInvalidator
import com.cappielloantonio.tempo.service.MediaBrowserTree
import com.cappielloantonio.tempo.ui.activity.CarHostActivity
import com.cappielloantonio.tempo.util.BrowseTabOrder
import com.cappielloantonio.tempo.util.BrowseTabOrderFixture
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.Preferences
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The car is told to rebuild its tabs when this screen stops, and only if the
 * saved order changed while it was open. Driven through a real CarHostActivity
 * rather than by calling the invalidator, so each test fails if the lifecycle
 * wiring is wrong.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class BrowseTabOrderFragmentLeaveTest {
    private val session = mock<MediaLibrarySession>()

    @Before
    fun setUp() {
        BrowseTabOrderFixture.clearSavedOrder()
        HomeUsersRepository.fetch = { null }
        MediaBrowserTree.initialize(App.getContext(), mock())
        BrowseTreeInvalidator.attach(session)
    }

    @After
    fun tearDown() {
        // BrowseTreeInvalidator is a process-wide singleton; leaving a mock
        // attached would leak into whatever test class runs next.
        BrowseTreeInvalidator.detach()
        HomeUsersRepository.resetForTest()
    }

    /**
     * Mirrors CarSettingsFragment's "Customize tabs" row: replace the
     * container and add to the back stack, which is what lets back pop it.
     */
    private fun openScreen(): ActivityController<CarHostActivity> {
        val controller = Robolectric.buildActivity(CarHostActivity::class.java).setup()
        controller
            .get()
            .supportFragmentManager
            .beginTransaction()
            .replace(R.id.car_host_container, BrowseTabOrderFragment())
            .addToBackStack(null)
            .commit()
        idle()
        return controller
    }

    /** The write a drag makes, demoting the first tab below the line. */
    private fun moveATabIntoMore() {
        val order = BrowseTabOrder.resolve(Preferences.getBrowseTabOrder()).toMutableList()
        BrowseTabOrderFragment.moveAndPersist(order, 0, order.lastIndex)
    }

    // invalidateNode posts to the main looper while invalidateRoot runs
    // synchronously, so one idle after leaving picks up both.
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun verifyInvalidated(times: Int) {
        verify(session, times(times)).notifyChildrenChanged(eq(Constants.ROOT_ID), any(), eq(null))
        verify(session, times(times)).notifyChildrenChanged(eq(Constants.MORE_ID), eq(0), eq(null))
    }

    private fun verifyNothingInvalidated() {
        verify(session, never()).notifyChildrenChanged(any<String>(), any(), anyOrNull())
    }

    @Test
    fun `backing out after a move invalidates both the root and More`() {
        val controller = openScreen()
        moveATabIntoMore()

        controller.get().onBackPressedDispatcher.onBackPressed()
        idle()

        verifyInvalidated(times = 1)
    }

    @Test
    fun `pressing Home after a move invalidates both the root and More`() {
        val controller = openScreen()
        moveATabIntoMore()

        controller.pause().stop()
        idle()

        verifyInvalidated(times = 1)
    }

    @Test
    fun `leaving without a move invalidates nothing`() {
        val controller = openScreen()

        controller.get().onBackPressedDispatcher.onBackPressed()
        idle()

        verifyNothingInvalidated()
    }

    @Test
    fun `a configuration change without a move invalidates nothing`() {
        val controller = openScreen()

        controller.recreate()
        idle()

        verifyNothingInvalidated()
    }

    @Test
    fun `a configuration change after a move invalidates once, and leaving after it does not again`() {
        val controller = openScreen()
        moveATabIntoMore()

        controller.recreate()
        idle()
        verifyInvalidated(times = 1)

        controller.get().onBackPressedDispatcher.onBackPressed()
        idle()
        verifyInvalidated(times = 1)
    }

    @Test
    fun `coming back and leaving again without a further move does not invalidate twice`() {
        val controller = openScreen()
        moveATabIntoMore()
        controller.pause().stop()
        idle()

        controller.restart().resume()
        controller.pause().stop()
        idle()

        verifyInvalidated(times = 1)
    }
}
