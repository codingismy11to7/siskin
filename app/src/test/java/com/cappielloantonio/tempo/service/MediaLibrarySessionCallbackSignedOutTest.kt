package com.cappielloantonio.tempo.service

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.repository.PlexBrowseRepository
import com.cappielloantonio.tempo.repository.SessionMediaItemRepository
import com.cappielloantonio.tempo.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The decision `onGetChildren`'s no-credentials guard encodes: the browse
 * root is a tab bar, not a list (see [MediaBrowserTree.signedOutRow]'s KDoc
 * for the four-round emulator measurement behind that). Signed out, the
 * root still returns its normal four tabs -- built by
 * [MediaBrowserTree.buildTree], which is static and needs no credentials --
 * and every *other* `parentId` gets the info row instead. This is the part
 * a future refactor of that guard would most easily break, since nothing
 * else in the type system distinguishes "root" from "everything else" here.
 *
 * Robolectric because the callback's constructor reads real string
 * resources for its CommandButtons, and the info row's title/subtitle come
 * from real string resources too.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class MediaLibrarySessionCallbackSignedOutTest {

    private val browseRepository = mock<PlexBrowseRepository>()
    private val sessionMediaItemRepository = mock<SessionMediaItemRepository>()
    private val controller = mock<MediaSession.ControllerInfo>()

    private lateinit var callback: MediaLibrarySessionCallback

    @Before
    fun setUp() {
        // Robolectric keeps these preferences in a static field between
        // methods, so every field the gate reads is cleared here rather
        // than assumed absent.
        PlexApi().apply {
            session = null
            accountToken = null
        }

        callback = MediaLibrarySessionCallback(
            RuntimeEnvironment.getApplication(),
            mock<BaseMediaService>(),
            browseRepository,
            sessionMediaItemRepository
        )

        // Mirrors the real media3 lifecycle -- the root is always requested
        // before anything subscribes to it -- and is what populates
        // MediaBrowserTree's treeNodes that ROOT_ID's children come from.
        callback.onGetLibraryRoot(mock(), controller, null).get()
    }

    @Test
    fun rootStillReturnsItsFourTabsWhileSignedOut() {
        val children = getChildren(Constants.ROOT_ID).map { it.mediaId }

        assertEquals(
            listOf(
                Constants.PLAYLIST_ID,
                Constants.ARTISTS_ID,
                Constants.ALBUMS_ID,
                Constants.MORE_ID
            ),
            children
        )
    }

    @Test
    fun aNonRootParentReturnsTheInfoRowWhileSignedOut() {
        listOf(
            Constants.PLAYLIST_ID,
            Constants.ARTISTS_ID,
            Constants.ALBUMS_ID,
            Constants.MORE_ID
        ).forEach { parentId ->
            val row = getChildren(parentId).single()

            assertEquals(
                RuntimeEnvironment.getApplication().getString(R.string.car_sign_in_required),
                row.mediaMetadata.title?.toString()
            )
            assertEquals(
                RuntimeEnvironment.getApplication().getString(R.string.car_sign_in_hint),
                row.mediaMetadata.artist?.toString()
            )
            assertEquals(true, row.mediaMetadata.isBrowsable)
            assertEquals(false, row.mediaMetadata.isPlayable)
        }
    }

    private fun getChildren(parentId: String) =
        callback.onGetChildren(
            mock<MediaLibraryService.MediaLibrarySession>(),
            controller,
            parentId,
            0,
            Constants.MAX_ITEMS,
            null
        ).get().value!!
}
