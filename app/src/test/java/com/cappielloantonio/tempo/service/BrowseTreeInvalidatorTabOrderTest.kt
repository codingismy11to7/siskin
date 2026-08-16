package com.cappielloantonio.tempo.service

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.cappielloantonio.tempo.repository.PlexBrowseRepository
import com.cappielloantonio.tempo.util.BrowseTabOrderFixture
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.Preferences
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import android.os.Looper

/**
 * A reorder must invalidate More as well as the root.
 *
 * Measured on the AAOS emulator: with invalidateRoot() alone the tab bar
 * redrew correctly on a demotion while More went on serving a cached list
 * with the demoted destination missing from it, until the user navigated away
 * and back. See the design doc's "Applying the change".
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class BrowseTreeInvalidatorTabOrderTest {

    private val session = mock<MediaLibrarySession>()

    @Before
    fun setUp() {
        BrowseTabOrderFixture.clearSavedOrder()
        MediaBrowserTree.initialize(RuntimeEnvironment.getApplication(), mock<PlexBrowseRepository>())
        BrowseTreeInvalidator.attach(session)
    }

    @After
    fun tearDown() {
        BrowseTreeInvalidator.detach()
    }

    @Test
    fun `applying an order notifies the root and More`() {
        Preferences.setBrowseTabOrder(listOf(Constants.DECADES_ID, Constants.ALBUMS_ID))

        BrowseTreeInvalidator.invalidateRoot()
        BrowseTreeInvalidator.invalidateNode(Constants.MORE_ID, 0)
        // invalidateNode posts to the main looper rather than requiring it.
        shadowOf(Looper.getMainLooper()).idle()

        // eq(null), not any(): mockito-kotlin's any() does not match a null
        // argument (it needs anyOrNull() for that), and the real call always
        // passes null for LibraryParams here -- see BrowseTreeInvalidatorTest
        // and CarHostActivityTest, which verify the same call shape the
        // same way.
        verify(session).notifyChildrenChanged(eq(Constants.ROOT_ID), any(), eq(null))
        verify(session).notifyChildrenChanged(eq(Constants.MORE_ID), eq(0), eq(null))
    }
}
