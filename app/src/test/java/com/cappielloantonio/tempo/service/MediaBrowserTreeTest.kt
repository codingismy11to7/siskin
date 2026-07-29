package com.cappielloantonio.tempo.service

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.repository.PlexBrowseRepository
import com.cappielloantonio.tempo.util.ConstantsAA
import com.cappielloantonio.tempo.util.ResourceUris
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@UnstableApi
class MediaBrowserTreeTest {

    @Before
    fun setUp() {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.getString(anyInt())).thenReturn("mock_string")
        MediaBrowserTree.initialize(context, mock<PlexBrowseRepository>())

        // ResourceUris.forResource() builds a real android.net.Uri via Uri.Builder,
        // an unmocked Android platform class. Under this module's
        // unitTests.returnDefaultValues = true, Uri.Builder's chained setters
        // return null instead of `this`, which NPEs on the second call in the
        // chain -- with no Robolectric available, statically mocking the one
        // Uri-producing call is the only way to exercise buildTree() here.
        val resourceUris = Mockito.mockStatic(ResourceUris::class.java)
        try {
            resourceUris.`when`<Uri> { ResourceUris.forResource(anyInt()) }.thenReturn(mock<Uri>())
            MediaBrowserTree.buildTree()
        } finally {
            resourceUris.close()
        }
    }

    @Test
    fun rootHasExactlyFourTabsInOrder() {
        val children = MediaBrowserTree.getChildren(ConstantsAA.ROOT_ID)
            .get().value!!.map { it.mediaId }

        assertEquals(
            listOf(
                ConstantsAA.PLAYLIST_ID,
                ConstantsAA.ARTISTS_ID,
                ConstantsAA.ALBUMS_ID,
                ConstantsAA.MORE_ID
            ),
            children
        )
    }

    @Test
    fun rootHasExactlyFourChildrenBecauseTheCarDropsTheFifth() {
        val children = MediaBrowserTree.getChildren(ConstantsAA.ROOT_ID).get().value!!

        assertEquals(4, children.size)
    }

    @Test
    fun removedTabsAreNotInTheTree() {
        listOf(
            "[homeID]",
            "[podcastID]",
            "[radioID]",
            "[genresID]",
            "[folderID]",
            "[downloadedID]"
        ).forEach { removed ->
            assertEquals("$removed should not resolve", null, MediaBrowserTree.getItem(removed))
        }
    }

    /**
     * Not decoration: media3's default onSubscribe drops the subscription unless
     * onGetItem returns a browsable item, and notifyChildrenChanged only reaches
     * live subscriptions. A null here makes every
     * BrowseTreeInvalidator.invalidateNode on a picker node a silent no-op, and
     * the tick stops moving under the user.
     */
    @Test
    fun pickerNodesResolveSoTheirSubscriptionsCanStick() {
        listOf(
            ConstantsAA.PICK_SERVER_ID + "abc123",
            ConstantsAA.PICK_LIBRARY_ID + "abc123|7"
        ).forEach { id ->
            val item = MediaBrowserTree.getItem(id)
                ?: throw AssertionError("$id must resolve or its subscription is dropped")
            assertEquals(id, item.mediaId)
            assertEquals(true, item.mediaMetadata.isBrowsable)
            // A playable row makes the car open Now Playing on tap, and nothing
            // the app returns can suppress that.
            assertEquals(false, item.mediaMetadata.isPlayable)
        }
    }

    @Test
    fun unknownIdsStillResolveToNothing() {
        assertEquals(null, MediaBrowserTree.getItem("[somethingElse]abc123"))
    }

    @Test
    fun artistsRootHasFolderArtistsMediaType() {
        val artistsItem = MediaBrowserTree.getItem(ConstantsAA.ARTISTS_ID)!!

        assertEquals(
            MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
            artistsItem.mediaMetadata.mediaType
        )
    }
}
