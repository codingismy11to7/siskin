package com.cappielloantonio.tempo.ui.fragment

import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.SectionKey
import com.cappielloantonio.tempo.repository.HomeUsersRepository
import com.cappielloantonio.tempo.service.BrowseTreeInvalidator
import com.cappielloantonio.tempo.service.MediaBrowserTree
import org.mockito.kotlin.mock

/**
 * What a test needs before CarHostActivity will route to Settings: a signed-in
 * session in preferences, and no Home users to fetch. The attached session
 * mock is what makes BrowseTreeInvalidator.invalidateNode() do anything at
 * all -- it returns early without one, so an assertion about it would pass
 * vacuously.
 */
internal object CarHostTestFixture {
    fun arrange(): MediaLibrarySession {
        PlexApi().session =
            PlexSession(
                accountToken = "t",
                serverUri = "https://example.invalid",
                musicSectionKey = SectionKey("1"),
                serverToken = null,
                owned = true,
            )
        val session = mock<MediaLibrarySession>()
        MediaBrowserTree.initialize(App.getContext(), mock())
        BrowseTreeInvalidator.attach(session)
        HomeUsersRepository.fetch = { null }
        return session
    }

    /** BrowseTreeInvalidator is a process-wide singleton; a leaked mock would reach the next test class. */
    fun tearDown() {
        BrowseTreeInvalidator.detach()
        HomeUsersRepository.resetForTest()
    }
}
