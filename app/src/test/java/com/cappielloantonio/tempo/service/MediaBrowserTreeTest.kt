package com.cappielloantonio.tempo.service

import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.repository.PlexBrowseRepository
import com.cappielloantonio.tempo.util.ConstantsAA
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class MediaBrowserTreeTest {

    @Before
    fun setUp() {
        // A real Application, not a mock: this class used to run without
        // Robolectric, where Uri.Builder's chained setters return null instead
        // of `this` under unitTests.returnDefaultValues = true, NPEing on the
        // second call in the chain -- statically mocking
        // ResourceUris.forResource() (buildTree()'s only Uri-producing call)
        // was the sole way to exercise it. @RunWith(RobolectricTestRunner)
        // makes Uri.Builder real, so that scaffolding is gone; a real Context
        // is what the two newest tests below already use via
        // RuntimeEnvironment.getApplication(), and this setUp now uses the
        // same one rather than a second, mocked source that answered
        // "mock_string" to every getString() call and was never actually
        // asserted on.
        val context = RuntimeEnvironment.getApplication()
        MediaBrowserTree.initialize(context, mock<PlexBrowseRepository>())
        MediaBrowserTree.buildTree()
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

    /**
     * One row, not two: this row is only ever returned for a *non-root*
     * `parentId` now (the root instead falls through to the normal four
     * tabs -- see [MediaBrowserTree.signedOutRow]'s KDoc for the full
     * measurement history), so there is no longer a root-level auto-drill
     * risk for it to guard against with a second row.
     */
    @Test
    fun `the signed-out row is browsable but not playable and carries both lines`() {
        val context = RuntimeEnvironment.getApplication()

        val rows = MediaBrowserTree.signedOutRow(context)

        assertEquals(1, rows.size)
        val row = rows.single()
        // Browsable: a row that is neither browsable nor playable is not
        // rendered at all on an AAOS API 33 emulator (measured, not
        // assumed -- see signedOutRow's KDoc), so it must be browsable to
        // be seen.
        assertEquals(true, row.mediaMetadata.isBrowsable)
        // Still not playable: tapping it must never attempt a stream.
        assertEquals(false, row.mediaMetadata.isPlayable)
        assertEquals(
            context.getString(R.string.car_sign_in_required),
            row.mediaMetadata.title
        )
        assertEquals(
            context.getString(R.string.car_sign_in_hint),
            row.mediaMetadata.artist
        )
    }

    /**
     * The row is browsable, which means the default `onSubscribe` will run
     * it through `onGetItem` (see [MediaBrowserTree.getItem]'s KDoc) before
     * a tap into it is allowed to stick -- same requirement the picker rows
     * already have, checked above in
     * [pickerNodesResolveSoTheirSubscriptionsCanStick].
     */
    @Test
    fun signedOutRowResolvesByItsOwnIdSoItsSubscriptionCanStick() {
        val context = RuntimeEnvironment.getApplication()

        val row = MediaBrowserTree.signedOutRow(context).single()
        val item = MediaBrowserTree.getItem(row.mediaId)
            ?: throw AssertionError(
                "${row.mediaId} must resolve or its subscription is dropped"
            )

        assertEquals(row.mediaId, item.mediaId)
        assertEquals(true, item.mediaMetadata.isBrowsable)
        assertEquals(false, item.mediaMetadata.isPlayable)
    }

    /**
     * Reachable while signed in, not just while signed out: drill into this
     * row signed out, sign in through the gear, and the car re-requests the
     * same node it was already showing -- now signed in, so
     * [MediaLibraryServiceCallback.onGetChildren]'s no-credentials guard no
     * longer intercepts it and the request reaches [MediaBrowserTree.getChildren]
     * directly. Every sibling ad-hoc row (the picker's confirmation row, the
     * message row) answers its own id with itself rather than an error; this
     * pins that the signed-out row does too, rather than falling through to
     * `ERROR_BAD_VALUE`.
     */
    @Test
    fun signedOutRowAnswersItsOwnIdWithItselfEvenWhenReachedSignedIn() {
        val context = RuntimeEnvironment.getApplication()
        val rowId = MediaBrowserTree.signedOutRow(context).single().mediaId

        val result = MediaBrowserTree.getChildren(rowId).get()

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        val row = result.value!!.single()
        assertEquals(
            context.getString(R.string.car_sign_in_required),
            row.mediaMetadata.title
        )
        assertEquals(
            context.getString(R.string.car_sign_in_hint),
            row.mediaMetadata.artist
        )
    }
}
