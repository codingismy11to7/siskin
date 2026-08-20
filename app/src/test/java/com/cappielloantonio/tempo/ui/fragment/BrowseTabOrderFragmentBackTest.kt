package com.cappielloantonio.tempo.ui.fragment

import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.service.BrowseTreeInvalidator
import com.cappielloantonio.tempo.service.MediaBrowserTree
import com.cappielloantonio.tempo.ui.activity.CarHostActivity
import com.cappielloantonio.tempo.util.BrowseTabOrderFixture
import com.cappielloantonio.tempo.util.Constants
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Task 7 wired this screen's back-pressed callback to call both
 * BrowseTreeInvalidator.invalidateRoot() and invalidateNode(MORE_ID, 0), but
 * the only prior coverage (BrowseTreeInvalidatorTabOrderTest) called the
 * invalidator directly and never touched the fragment -- it would still pass
 * if the callback wiring were deleted from onCreateView entirely. This drives
 * the real callback, registered on the real dispatcher, by getting the
 * fragment on screen the way CarSettingsFragment's "Customize tabs" row does
 * and then pressing back, so it fails if either call is missing.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class BrowseTabOrderFragmentBackTest {
    private val session = mock<MediaLibrarySession>()

    @Before
    fun setUp() {
        BrowseTabOrderFixture.clearSavedOrder()
        MediaBrowserTree.initialize(App.getContext(), mock())
        BrowseTreeInvalidator.attach(session)
    }

    @After
    fun tearDown() {
        // BrowseTreeInvalidator is a process-wide singleton; leaving a mock
        // attached would leak into whatever test class runs next.
        BrowseTreeInvalidator.detach()
    }

    @Test
    fun `leaving the screen invalidates both the root and More`() {
        val controller = Robolectric.buildActivity(CarHostActivity::class.java).setup()
        val activity = controller.get()

        // Mirrors CarSettingsFragment's "Customize tabs" row: replace the
        // container and add to the back stack. addToBackStack matters here --
        // it is what lets FragmentManager's own back-stack-pop callback take
        // over once this fragment's callback disables itself and re-dispatches.
        activity.supportFragmentManager
            .beginTransaction()
            .replace(R.id.car_host_container, BrowseTabOrderFragment())
            .addToBackStack(null)
            .commit()
        shadowOf(Looper.getMainLooper()).idle()

        activity.onBackPressedDispatcher.onBackPressed()
        // invalidateNode posts to the main looper while invalidateRoot runs
        // synchronously, so one idle after the press picks up both.
        shadowOf(Looper.getMainLooper()).idle()

        verify(session).notifyChildrenChanged(eq(Constants.ROOT_ID), any(), eq(null))
        verify(session).notifyChildrenChanged(eq(Constants.MORE_ID), eq(0), eq(null))
    }
}
