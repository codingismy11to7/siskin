package com.cappielloantonio.tempo.service

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.repository.AutomotiveRepository
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
        MediaBrowserTree.initialize(context, mock<AutomotiveRepository>())

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
    fun rootHasExactlyThreeTabsInOrder() {
        val children = MediaBrowserTree.getChildren(ConstantsAA.ROOT_ID)
            .get().value!!.map { it.mediaId }

        assertEquals(
            listOf(ConstantsAA.PLAYLIST_ID, ConstantsAA.ARTISTS_ID, ConstantsAA.ALBUMS_ID),
            children
        )
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

    @Test
    fun artistsRootHasFolderArtistsMediaType() {
        val artistsItem = MediaBrowserTree.getItem(ConstantsAA.ARTISTS_ID)!!

        assertEquals(
            MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
            artistsItem.mediaMetadata.mediaType
        )
    }
}
