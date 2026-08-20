package com.cappielloantonio.tempo.service

import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.repository.PlexBrowseRepository
import com.cappielloantonio.tempo.util.BrowseContentStyle
import com.cappielloantonio.tempo.util.BrowseTabOrderFixture
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class MediaBrowserTreeTest {
    @Before
    fun setUp() {
        // Robolectric caches SharedPreferences statically across test methods
        // (and across test classes in the same JVM fork). This class writes
        // the by-initial key and reads the tab-order key, so without this
        // reset -- done before the build below -- one method's write would
        // decide another's result, and a later-running class would inherit it
        // too.
        App
            .getInstance()
            .preferences
            .edit()
            .remove("artists_by_initial")
            .commit()
        BrowseTabOrderFixture.clearSavedOrder()

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
        val children =
            MediaBrowserTree
                .getChildren(Constants.ROOT_ID)
                .get()
                .value!!
                .map { it.mediaId }

        assertEquals(
            listOf(
                Constants.PLAYLIST_ID,
                Constants.ARTISTS_ID,
                Constants.ALBUMS_ID,
                Constants.MORE_ID,
            ),
            children,
        )
    }

    @Test
    fun rootHasExactlyFourChildrenBecauseTheCarDropsTheFifth() {
        val children = MediaBrowserTree.getChildren(Constants.ROOT_ID).get().value!!

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
            "[downloadedID]",
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
            Constants.PICK_SERVER_ID + "abc123",
            Constants.PICK_LIBRARY_ID + "abc123|7",
        ).forEach { id ->
            val item =
                MediaBrowserTree.getItem(id)
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
        val artistsItem = MediaBrowserTree.getItem(Constants.ARTISTS_ID)!!

        assertEquals(
            MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
            artistsItem.mediaMetadata.mediaType,
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
            row.mediaMetadata.title,
        )
        assertEquals(
            context.getString(R.string.car_sign_in_hint),
            row.mediaMetadata.artist,
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
        val item =
            MediaBrowserTree.getItem(row.mediaId)
                ?: throw AssertionError(
                    "${row.mediaId} must resolve or its subscription is dropped",
                )

        assertEquals(row.mediaId, item.mediaId)
        assertEquals(true, item.mediaMetadata.isBrowsable)
        assertEquals(false, item.mediaMetadata.isPlayable)
    }

    /**
     * Reachable while signed in, not just while signed out: drill into this
     * row signed out, sign in through the gear, and the car re-requests the
     * same node it was already showing -- now signed in, so
     * [MediaLibrarySessionCallback.onGetChildren]'s no-credentials guard no
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
            row.mediaMetadata.title,
        )
        assertEquals(
            context.getString(R.string.car_sign_in_hint),
            row.mediaMetadata.artist,
        )
    }

    @Test
    fun moreOffersDiscoverFirst() {
        val children =
            MediaBrowserTree
                .getChildren(Constants.MORE_ID)
                .get()
                .value!!
                .map { it.mediaId }

        assertEquals(
            listOf(Constants.DISCOVER_ID, Constants.DECADES_ID, Constants.SELECT_LIBRARY_ID),
            children,
        )
    }

    /**
     * A list, permanently -- see the comment on the Discover node in
     * buildTree(). A hub row's meaning is its sentence ("Haven't played in 5
     * months"), which a grid's caption truncates, and the server-supplied
     * titles vary in length across five locales.
     */
    @Test
    fun discoverChildrenAreAListNotAGrid() {
        val discover = MediaBrowserTree.getItem(Constants.DISCOVER_ID)!!
        val style =
            discover.mediaMetadata.extras!!
                .getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE)

        assertEquals(BrowseContentStyle.browsableChildStyle(false), style)
    }

    @Test
    fun theDecadesRowIsBrowsableAndNotPlayable() {
        val item = MediaBrowserTree.getItem(Constants.DECADES_ID)!!

        assertEquals(true, item.mediaMetadata.isBrowsable)
        assertEquals(false, item.mediaMetadata.isPlayable)
    }

    @Test
    fun theDecadesNodeAsksForAGridNowThatItsRowsHaveArtwork() {
        val item = MediaBrowserTree.getItem(Constants.DECADES_ID)!!

        assertEquals(
            BrowseContentStyle.browsableChildStyle(true),
            item.mediaMetadata.extras!!.getInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            ),
        )
    }

    @Test
    fun aWindowIdRoutesToItsOwnSliceRatherThanToAnItem() {
        // "[artistWindowID]50" must not be mistaken for "[artistID]..." -- the
        // window tests run first for exactly that reason.
        assertTrue(Constants.ARTIST_WINDOW_ID.startsWith("[artist"))
        assertFalse(Constants.ARTIST_WINDOW_ID.startsWith(Constants.ARTIST_ID))
        assertFalse(Constants.ALBUM_WINDOW_ID.startsWith(Constants.ALBUM_ID))

        // The assertions above only rule out a spelling collision between the
        // id constants; they never call getChildren, so a wrong branch ORDER
        // in the routing below, or a swapped prefix argument, would still pass
        // them. This exercises the routing itself.
        val repository = mock<PlexBrowseRepository>()
        MediaBrowserTree.initialize(RuntimeEnvironment.getApplication(), repository)

        MediaBrowserTree.getChildren(Constants.ARTIST_WINDOW_ID + "50")
        verify(repository).getArtistWindow(50, Constants.ARTIST_ID)

        MediaBrowserTree.getChildren(Constants.ALBUM_WINDOW_ID + "50")
        verify(repository).getAlbumWindow(50, Constants.ALBUM_ID)
    }

    @Test
    fun theArtistsAndAlbumsTabsRenderTheirChildrenAsLists() {
        // List under both settings, deliberately. buildTree() runs on
        // onGetLibraryRoot, before any library has been queried, so a style that
        // depended on the preference would need the root invalidated and visibly
        // re-rendered on every toggle. A one-character letter label needs no
        // width, so a list costs it nothing.
        Preferences.setArtistsByInitialEnabled(true)
        MediaBrowserTree.buildTree()
        assertEquals(
            BrowseContentStyle.browsableChildStyle(false),
            MediaBrowserTree
                .getItem(Constants.ARTISTS_ID)!!
                .mediaMetadata.extras!!
                .getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE),
        )

        // Their children are window rows carrying no artwork of their own; a grid
        // of placeholders is worse than a list.
        //
        // The Decades node next door asks for the opposite, and the two do not
        // contradict each other: a decade row wears a composite tiled from four
        // real covers, so a grid there shows something. A window row has nothing
        // but the range it spans ("Beck  -  Cake"), which is text, and text reads
        // better in a list.
        //
        // Flip the setting for this second block so the two blocks actually
        // cover both values -- otherwise both would run under `true` (the
        // default when the preference key is absent) and a regression that
        // wired browsableChildrenAsGrid to the preference would pass unnoticed.
        Preferences.setArtistsByInitialEnabled(false)
        MediaBrowserTree.buildTree()
        val artists = MediaBrowserTree.getItem(Constants.ARTISTS_ID)!!
        assertEquals(
            BrowseContentStyle.browsableChildStyle(false),
            artists.mediaMetadata.extras!!.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE),
        )
        val albums = MediaBrowserTree.getItem(Constants.ALBUMS_ID)!!
        assertEquals(
            BrowseContentStyle.browsableChildStyle(false),
            albums.mediaMetadata.extras!!.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE),
        )
    }

    @Test
    fun theArtistsTabFollowsTheByInitialPreference() {
        val repository = mock<PlexBrowseRepository>()
        MediaBrowserTree.initialize(RuntimeEnvironment.getApplication(), repository)

        Preferences.setArtistsByInitialEnabled(true)
        MediaBrowserTree.getChildren(Constants.ARTISTS_ID)
        verify(repository).getArtistLetters(Constants.ARTIST_LETTER_ID, Constants.ARTIST_ID)

        Preferences.setArtistsByInitialEnabled(false)
        MediaBrowserTree.getChildren(Constants.ARTISTS_ID)
        verify(repository).getArtistWindows(Constants.ARTIST_WINDOW_ID, Constants.ARTIST_ID)
    }

    @Test
    fun theAlbumsTabIgnoresTheByInitialPreference() {
        // Album buckets B=350 and S=315 are over the car's ceiling, so the
        // setting is not offered there and must not leak into this tab.
        val repository = mock<PlexBrowseRepository>()
        MediaBrowserTree.initialize(RuntimeEnvironment.getApplication(), repository)

        Preferences.setArtistsByInitialEnabled(true)
        MediaBrowserTree.getChildren(Constants.ALBUMS_ID)

        verify(repository).getAlbumWindows(Constants.ALBUM_WINDOW_ID, Constants.ALBUM_ID)
    }

    @Test
    fun aLetterIdRoutesToItsBucketRatherThanToAnItem() {
        // "[artistLetterID]A" must not be mistaken for "[artistID]..." -- the
        // letter test has to come first in the routing for the same reason the
        // window test does.
        assertFalse(Constants.ARTIST_LETTER_ID.startsWith(Constants.ARTIST_ID))
        assertFalse(Constants.ARTIST_ID.startsWith(Constants.ARTIST_LETTER_ID))

        val repository = mock<PlexBrowseRepository>()
        MediaBrowserTree.initialize(RuntimeEnvironment.getApplication(), repository)

        MediaBrowserTree.getChildren(Constants.ARTIST_LETTER_ID + "A")
        verify(repository).getArtistLetter("A", Constants.ARTIST_ID)

        // The encoded key reaches the repository untouched: decoding it here
        // would send "%2523" back to the server.
        MediaBrowserTree.getChildren(Constants.ARTIST_LETTER_ID + "%23")
        verify(repository).getArtistLetter("%23", Constants.ARTIST_ID)
    }

    /**
     * The saved order decides the root. Decades is promoted from More and
     * Playlists demoted into it, which is the swap measured on the emulator --
     * see the spec's "What was measured".
     */
    @Test
    fun `a saved order decides which three ids are tabs and their order`() {
        Preferences.setBrowseTabOrder(
            listOf(
                Constants.DECADES_ID,
                Constants.ALBUMS_ID,
                Constants.ARTISTS_ID,
                Constants.PLAYLIST_ID,
            ),
        )
        MediaBrowserTree.buildTree()

        val children =
            MediaBrowserTree
                .getChildren(Constants.ROOT_ID)
                .get()
                .value!!
                .map { it.mediaId }

        assertEquals(
            listOf(
                Constants.DECADES_ID,
                Constants.ALBUMS_ID,
                Constants.ARTISTS_ID,
                Constants.MORE_ID,
            ),
            children,
        )
    }

    /**
     * The saved order here predates Discover, so resolve() appends it -- which
     * is why Discover follows Playlists rather than leading More. That is the
     * upgrade case the spec describes, arriving at the tree.
     */
    @Test
    fun `More holds what the order left over, with Select Library pinned last`() {
        Preferences.setBrowseTabOrder(
            listOf(
                Constants.DECADES_ID,
                Constants.ALBUMS_ID,
                Constants.ARTISTS_ID,
                Constants.PLAYLIST_ID,
            ),
        )
        MediaBrowserTree.buildTree()

        val children =
            MediaBrowserTree
                .getChildren(Constants.MORE_ID)
                .get()
                .value!!
                .map { it.mediaId }

        assertEquals(
            listOf(Constants.PLAYLIST_ID, Constants.DISCOVER_ID, Constants.SELECT_LIBRARY_ID),
            children,
        )
    }

    /**
     * Discover reorders like every other destination, which is what the tab
     * order feature had to preserve when Discover landed beside it: promoted to
     * position 0 it is a root tab, it leaves More entirely, and it keeps the
     * list styling its own node declares. The default case -- Discover as
     * More's first row -- is covered by moreOffersDiscoverFirst above.
     */
    @Test
    fun `Discover is reorderable like any other destination`() {
        Preferences.setBrowseTabOrder(
            listOf(
                Constants.DISCOVER_ID,
                Constants.PLAYLIST_ID,
                Constants.ARTISTS_ID,
                Constants.ALBUMS_ID,
                Constants.DECADES_ID,
            ),
        )
        MediaBrowserTree.buildTree()

        val root =
            MediaBrowserTree
                .getChildren(Constants.ROOT_ID)
                .get()
                .value!!
                .map { it.mediaId }
        assertEquals(
            listOf(
                Constants.DISCOVER_ID,
                Constants.PLAYLIST_ID,
                Constants.ARTISTS_ID,
                Constants.MORE_ID,
            ),
            root,
        )

        val more =
            MediaBrowserTree
                .getChildren(Constants.MORE_ID)
                .get()
                .value!!
                .map { it.mediaId }
        assertEquals(
            listOf(Constants.ALBUMS_ID, Constants.DECADES_ID, Constants.SELECT_LIBRARY_ID),
            more,
        )

        // Promotion is free: the node carries its own presentation, so Discover
        // is still a list at root -- see the comment on its node in buildTree().
        val discover = MediaBrowserTree.getItem(Constants.DISCOVER_ID)!!
        assertEquals(
            BrowseContentStyle.browsableChildStyle(false),
            discover.mediaMetadata.extras!!.getInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            ),
        )
    }

    /**
     * More is the fourth tab under every order, because Select Library lives
     * inside it and is the only route to switching libraries.
     */
    @Test
    fun `More is always the fourth tab and never itself reorderable`() {
        listOf(
            listOf(Constants.DECADES_ID, Constants.PLAYLIST_ID, Constants.ALBUMS_ID),
            listOf(Constants.ALBUMS_ID, Constants.ARTISTS_ID, Constants.DECADES_ID),
            emptyList(),
        ).forEach { order ->
            Preferences.setBrowseTabOrder(order)
            MediaBrowserTree.buildTree()

            val children =
                MediaBrowserTree
                    .getChildren(Constants.ROOT_ID)
                    .get()
                    .value!!
                    .map { it.mediaId }

            assertEquals("root must stay at four for $order", 4, children.size)
            assertEquals("More must be last for $order", Constants.MORE_ID, children.last())
        }
    }

    /**
     * Promotion must be free: a destination carries its own presentation
     * wherever it sits. Measured on the emulator -- Decades at root position 0
     * rendered as a grid with its composite artwork -- and pinned here so a
     * refactor cannot quietly tie style to position.
     */
    @Test
    fun `a promoted destination keeps its own content style and media type`() {
        Preferences.setBrowseTabOrder(listOf(Constants.DECADES_ID))
        MediaBrowserTree.buildTree()

        val decades = MediaBrowserTree.getItem(Constants.DECADES_ID)!!
        assertEquals(
            BrowseContentStyle.browsableChildStyle(true),
            decades.mediaMetadata.extras!!.getInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            ),
        )
        assertEquals(
            MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            decades.mediaMetadata.mediaType,
        )
    }
}
