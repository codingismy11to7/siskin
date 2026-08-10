package com.cappielloantonio.tempo.plex

import androidx.annotation.OptIn
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.plex.models.Media
import com.cappielloantonio.tempo.plex.models.Metadata
import com.cappielloantonio.tempo.plex.models.Part
import com.cappielloantonio.tempo.provider.AlbumArtContentProvider
import com.cappielloantonio.tempo.util.BrowseContentStyle
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.ConstantsAA
import com.cappielloantonio.tempo.util.DecadeKey
import com.cappielloantonio.tempo.util.ResourceUris
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The mapper's actual output. Robolectric rather than plain JUnit because
 * returnDefaultValues makes Bundle discard every write and hand back null, under
 * which these assertions would pass against any implementation at all.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class PlexMediaMapperAssemblyTest {

    private val serverUri = "https://plex.example"
    private val token = "tok123"

    private fun track() = Metadata().apply {
        ratingKey = "1234"
        type = "track"
        title = "Song Title"
        parentTitle = "Album Title"
        grandparentTitle = "Artist Name"
        grandparentRatingKey = "77"
        thumb = "/library/metadata/1234/thumb/1699999999"
        duration = 250_000L
        index = 3
        year = 2021
        media = listOf(Media().apply { part = listOf(Part().apply { key = "/library/parts/9/file.flac" }) })
    }

    @Test
    fun carriesEveryBundleKeyLiveCodeReads() {
        val item = PlexMediaMapper.trackToMediaItem(track(), "parent-42", serverUri, token)!!
        val extras = item.mediaMetadata.extras!!

        assertEquals("1234", extras.getString(PlexMediaMapper.EXTRA_ID))
        assertEquals("77", extras.getString(PlexMediaMapper.EXTRA_ARTIST_ID))
        assertEquals("parent-42", extras.getString(PlexMediaMapper.EXTRA_PARENT_ID))
        assertEquals("/library/parts/9/file.flac", extras.getString(PlexMediaMapper.EXTRA_PART_KEY))
        assertEquals("/library/metadata/1234/thumb/1699999999", extras.getString(PlexMediaMapper.EXTRA_THUMB))
        assertEquals(Constants.MEDIA_TYPE_MUSIC, extras.getString(PlexMediaMapper.EXTRA_TYPE))
    }

    @Test
    fun buildsAPlayableStreamUrlFromThePartKey() {
        val item = PlexMediaMapper.trackToMediaItem(track(), null, serverUri, token)!!
        val uri = item.localConfiguration!!.uri.toString()

        assertTrue(uri, uri.startsWith("https://plex.example/library/parts/9/file.flac"))
        assertTrue(uri, uri.contains("X-Plex-Token=tok123"))
        assertEquals(uri, extrasUri(item))
    }

    private fun extrasUri(item: androidx.media3.common.MediaItem) =
        item.mediaMetadata.extras!!.getString(PlexMediaMapper.EXTRA_URI)

    @Test
    fun surfacesTitleArtistAndAlbumTheCarDisplays() {
        val item = PlexMediaMapper.trackToMediaItem(track(), null, serverUri, token)!!

        assertEquals("1234", item.mediaId)
        assertEquals("Song Title", item.mediaMetadata.title)
        assertEquals("Artist Name", item.mediaMetadata.artist)
        assertEquals("Album Title", item.mediaMetadata.albumTitle)
        assertEquals(3, item.mediaMetadata.trackNumber)
        assertEquals(2021, item.mediaMetadata.releaseYear)
        assertTrue(item.mediaMetadata.isPlayable!!)
    }

    @Test
    fun artworkUriSurvivesTheMultiSegmentThumbPath() {
        // Uri.Builder.appendPath escapes the separators and getLastPathSegment
        // decodes them, so the whole Plex thumb path comes back intact. Asserted
        // here because the round trip is not obvious from reading either side.
        val item = PlexMediaMapper.trackToMediaItem(track(), null, serverUri, token)!!
        val artwork = item.mediaMetadata.artworkUri!!

        assertEquals("/library/metadata/1234/thumb/1699999999", artwork.lastPathSegment)
    }

    @Test
    fun rebuildsTheStreamUrlAgainstCurrentCredentialsRatherThanStoredOnes() {
        // The bug partKey exists to prevent: a Plex stream URL carries the token,
        // so a persisted URL breaks when the token rotates. Same part key, new
        // token, must yield a URL with the new token.
        val first = PlexMediaMapper.trackToMediaItem(track(), null, serverUri, "old-token")!!
        val second = PlexMediaMapper.trackToMediaItem(track(), null, serverUri, "new-token")!!

        assertTrue(first.localConfiguration!!.uri.toString().contains("old-token"))
        assertTrue(second.localConfiguration!!.uri.toString().contains("new-token"))
    }

    @Test
    fun aTrackWithNoPlayablePartStillMapsWithoutCrashing() {
        // A queue restore that hits one unplayable entry must not abort the batch;
        // the Subsonic original crashed the app on launch this way (issue #705).
        val noPart = track().apply { media = null }
        val item = PlexMediaMapper.trackToMediaItem(noPart, null, serverUri, token)

        assertNotNull(item)
        assertEquals("1234", item!!.mediaId)
    }

    @Test
    fun returnsNullForMetadataWithNoRatingKey() {
        val unusable = Metadata().apply { type = "track"; title = "Nameless" }
        assertEquals(null, PlexMediaMapper.trackToMediaItem(unusable, null, serverUri, token))
    }

    @Test
    fun browsableItemsAreBrowsableAndNotPlayable() {
        val album = PlexMediaMapper.albumToMediaItem(
            Metadata().apply { ratingKey = "55"; type = "album"; title = "Album"; parentTitle = "Artist" },
            "[albumID]"
        )!!

        assertEquals("[albumID]55", album.mediaId)
        assertTrue(album.mediaMetadata.isBrowsable!!)
        assertEquals(false, album.mediaMetadata.isPlayable)
    }

    @Test
    fun heartStateIsPublishedAsAHeartRating() {
        val hearted = PlexMediaMapper.buildTrackMediaItem(
            ratingKey = "1234", title = "T", albumTitle = "A", artist = "R",
            thumb = null, partKey = "/p", durationMs = 1L, trackIndex = 1, year = 2020,
            grandparentRatingKey = "77", isHearted = true,
            parentId = null, serverUri = serverUri, token = token
        )

        // Load-bearing, and specifically as a HeartRating: com.android.car.media
        // reads the rating type off this subtype and draws its own control left of
        // transport for a heart. Publish nothing and there is no control; publish a
        // StarRating and the car ignores it. Measured -- see the 2026-08-02 design.
        assertEquals(HeartRating(true), hearted.mediaMetadata.userRating)
    }

    @Test
    fun aPlexRatingOfTenArrivesAsAFilledHeart() {
        val rated = PlexMediaMapper.trackToMediaItem(
            track().apply { userRating = 10.0 }, null, serverUri, token
        )!!
        assertEquals(HeartRating(true), rated.mediaMetadata.userRating)
    }

    @Test
    fun anUnratedPlexTrackArrivesAsAnUnfilledHeart() {
        // Not an absent rating: the control is only drawn when the field is
        // published, so an unhearted track has to publish HeartRating(false)
        // rather than nothing at all.
        val mapped = PlexMediaMapper.trackToMediaItem(track(), null, serverUri, token)!!
        assertEquals(HeartRating(false), mapped.mediaMetadata.userRating)
    }

    @Test
    fun readTrackFieldsRecoversEverythingTheEntitiesPersist() {
        // The shared reader both Room entities use. If a field drops out here,
        // a restored queue loses it silently -- no crash, just a track with no
        // artist or no part key, which is unplayable.
        val item = PlexMediaMapper.trackToMediaItem(
            track().apply { userRating = 10.0 }, "parent-42", serverUri, token
        )!!
        val fields = PlexMediaMapper.readTrackFields(item)!!

        assertEquals("1234", fields.ratingKey)
        assertEquals("Song Title", fields.title)
        assertEquals("Album Title", fields.albumTitle)
        assertEquals("Artist Name", fields.artist)
        assertEquals("/library/metadata/1234/thumb/1699999999", fields.thumb)
        assertEquals("/library/parts/9/file.flac", fields.partKey)
        assertEquals(250_000L, fields.durationMs)
        assertEquals(3, fields.trackIndex)
        assertEquals(2021, fields.year)
        assertEquals("77", fields.grandparentRatingKey)
        assertTrue(fields.isHearted)
    }

    @Test
    fun readTrackFieldsReturnsNullForANullItem() {
        assertEquals(null, PlexMediaMapper.readTrackFields(null))
    }

    @Test
    fun readTrackFieldsReturnsNullWhenRatingKeyIsBlank() {
        // No EXTRA_ID in the extras, and mediaId left at media3's default
        // (the empty string) rather than unset -- this is the
        // ratingKey.isNullOrBlank() branch, distinct from the item == null
        // guard covered above.
        val item = MediaItem.Builder().build()
        assertEquals(MediaItem.DEFAULT_MEDIA_ID, item.mediaId)

        assertEquals(null, PlexMediaMapper.readTrackFields(item))
    }

    // ── playlist artwork ──────────────────────────────────────

    private fun playlist(
        ratingKey: String = "169077",
        title: String = "❤️ Tracks",
        composite: String? = null,
        thumb: String? = null
    ) = Metadata().apply {
        this.ratingKey = ratingKey
        this.type = "playlist"
        this.title = title
        this.composite = composite
        this.thumb = thumb
    }

    @Test
    fun aPlaylistWithACompositeAndNoThumbGetsArtworkNotThePlaceholder() {
        // Plex never gives a playlist a thumb -- it generates composite, a
        // mosaic of the playlist's own tracks, instead. Measured from a real
        // server: "❤️ Tracks" has composite=/playlists/169077/composite/1781213364
        // and thumb=ABSENT.
        val item = PlexMediaMapper.playlistToMediaItem(
            playlist(composite = "/playlists/169077/composite/1781213364"),
            "[playlistID]"
        )!!

        val artwork = item.mediaMetadata.artworkUri!!
        assertEquals("content", artwork.scheme)
        assertEquals("/playlists/169077/composite/1781213364", artwork.lastPathSegment)
    }

    @Test
    fun aPlaylistWithNeitherCompositeNorThumbFallsBackToThePlaceholder() {
        // "punk goes pop" on the measured server had neither field -- Plex has
        // no art for it at all, so the placeholder icon must still show.
        val item = PlexMediaMapper.playlistToMediaItem(
            playlist(ratingKey = "1", title = "punk goes pop"),
            "[playlistID]"
        )!!

        val artwork = item.mediaMetadata.artworkUri!!
        assertEquals("android.resource", artwork.scheme)
        assertEquals(
            ResourceUris.forResource(R.drawable.ic_aa_playlist),
            artwork
        )
    }

    @Test
    fun anUnheartedTrackReadsAsUnhearted() {
        val unhearted = PlexMediaMapper.buildTrackMediaItem(
            ratingKey = "1234", title = "T", albumTitle = "A", artist = "R",
            thumb = null, partKey = "/p", durationMs = 1L, trackIndex = 1, year = 2020,
            grandparentRatingKey = "77", isHearted = false,
            parentId = null, serverUri = serverUri, token = token
        )

        assertEquals(HeartRating(false), unhearted.mediaMetadata.userRating)
        assertFalse(PlexMediaMapper.readTrackFields(unhearted)!!.isHearted)
    }

    // ── decade rows ───────────────────────────────────────────

    @Test
    fun aDecadeBecomesABrowsableRowCarryingItsKey() {
        // Robolectric rather than plain JUnit: decadeToMediaItem now always
        // builds a content Uri for its artwork, and android.net.Uri.Builder's
        // chained setters return null under returnDefaultValues, NPEing before
        // the MediaItem is even built -- not just handing back an unset field.
        val item = PlexMediaMapper.decadeToMediaItem(decade(), ConstantsAA.DECADE_ID, SCOPE, bucket = 487234L)!!

        assertEquals(ConstantsAA.DECADE_ID + DecadeKey.of(SCOPE, "1980"), item.mediaId)
        assertEquals("1980s", item.mediaMetadata.title)
        assertTrue(item.mediaMetadata.isBrowsable!!)
        // Never playable: a playable row opens Now Playing on tap, and a decade
        // has no single track to point at.
        assertFalse(item.mediaMetadata.isPlayable!!)
    }

    @Test
    fun aDecadesPlayableChildrenGetTheListStyleHint() {
        // A decade's children -- the shuffle row plus up to 500 tracks -- are
        // all playable. Without this hint the car falls back to its own
        // default, which may be a grid of identical album art (see
        // BrowseContentStyle.PLAYABLE_CHILD_STYLE).
        val item = PlexMediaMapper.decadeToMediaItem(decade(), ConstantsAA.DECADE_ID, SCOPE, bucket = 487234L)!!
        val extras = item.mediaMetadata.extras!!

        assertEquals(
            BrowseContentStyle.PLAYABLE_CHILD_STYLE,
            extras.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE)
        )
    }

    @Test
    fun aDecadeSetsNoBrowsableChildStyleBecauseItHasNoBrowsableChildren() {
        // A decade's only children are the shuffle row and tracks -- nothing
        // browsable -- so EXTRAS_KEY_CONTENT_STYLE_BROWSABLE is deliberately
        // left unset rather than set to some default; setting it would hint
        // at a grid of content that never renders.
        val item = PlexMediaMapper.decadeToMediaItem(decade(), ConstantsAA.DECADE_ID, SCOPE, bucket = 487234L)!!
        val extras = item.mediaMetadata.extras!!

        assertFalse(extras.containsKey(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
    }

    @Test
    fun aDecadeCarriesTheCompositeForItsScopeAndBucket() {
        // Both arguments are passed through, and both have to be: the URI is
        // what the car's image cache keys on, so a row that dropped either
        // would pin one tile across an hour's roll or across a switch to
        // another server. A distinctive scope is used rather than a plausible
        // one so a mapper that ignored it fails here rather than coincidentally
        // agreeing.
        val item = PlexMediaMapper.decadeToMediaItem(
            decade(), ConstantsAA.DECADE_ID, SCOPE, bucket = 487234L
        )!!

        assertEquals(
            AlbumArtContentProvider.decadeContentUri(SCOPE, "1980", 487234L),
            item.mediaMetadata.artworkUri
        )
    }

    /**
     * The regression test for the crash, and the property whose absence caused
     * it.
     *
     * `com.android.car.media` keeps its Decades adapter across a server switch
     * and diffs the old list against the new one. Every other browsable row is
     * keyed by a Plex ratingKey, which is server-specific, so a switch is always
     * remove-then-insert. A decade key is "1980" on every server: with the
     * library only in the artworkUri, DiffUtil saw the *same* row with changed
     * contents, emitted a change alongside the removals for the decades the new
     * library does not have, and `BatchingListUpdateCallback` flushed that
     * change as the removal arrived -- binding a position valid for the old list
     * against the already-latched shorter one, IndexOutOfBoundsException out of
     * `BrowseAdapter.onBindViewHolder`. Binding straight off a change is the
     * car app's defect and not ours to fix; not handing it a change is.
     *
     * Both rows are the same decade, the same bucket and the same title, so the
     * ids are the only thing that can differ -- which is exactly the shape of
     * the two adapters being diffed.
     */
    @Test
    fun twoLibrariesMintDifferentMediaIdsForTheSameDecade() {
        val onServerA =
            PlexMediaMapper.decadeToMediaItem(decade(), ConstantsAA.DECADE_ID, "serverA-4", 487234L)!!
        val onServerB =
            PlexMediaMapper.decadeToMediaItem(decade(), ConstantsAA.DECADE_ID, "serverB-4", 487234L)!!

        assertNotEquals(onServerA.mediaId, onServerB.mediaId)
    }

    /**
     * The other half of it: the id has to keep carrying the decade in the form
     * `PlexBrowseRepository` can filter Plex on, or every decade browses empty.
     * A mapper that hashed the two together would pass the test above.
     */
    @Test
    fun aDecadeRowsIdStillYieldsTheBareDecade() {
        val item = PlexMediaMapper.decadeToMediaItem(
            decade(), ConstantsAA.DECADE_ID, SCOPE, bucket = 487234L
        )!!

        assertEquals(
            "1980",
            DecadeKey.decadeIn(item.mediaId.removePrefix(ConstantsAA.DECADE_ID))
        )
    }

    // ── window rows ───────────────────────────────────────────

    @Test
    fun windowRowIsBrowsableAndNotPlayable() {
        val row = PlexMediaMapper.groupRowToMediaItem(
            "[artistWindowID]50", "Beck  -  Cake", R.drawable.ic_aa_artists
        )
        assertEquals("[artistWindowID]50", row.mediaId)
        assertEquals("Beck  -  Cake", row.mediaMetadata.title)
        assertEquals(true, row.mediaMetadata.isBrowsable)
        assertEquals(false, row.mediaMetadata.isPlayable)
    }

    @Test
    fun windowRowSetsBrowsableChildStyleToAGridBecauseItsChildrenAreArtistsOrAlbums() {
        // The property that makes a window's children (artists or albums)
        // render as a grid rather than a list. An implementation that dropped
        // .setExtras(extras) entirely would still pass every other window-row
        // assertion in this file, which is why this needs its own test rather
        // than piggybacking on windowRowIsBrowsableAndNotPlayable.
        val row = PlexMediaMapper.groupRowToMediaItem(
            "[artistWindowID]0", "A  -  B", R.drawable.ic_aa_artists
        )
        val extras = row.mediaMetadata.extras!!

        assertEquals(
            BrowseContentStyle.browsableChildStyle(true),
            extras.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE)
        )
    }

    @Test
    fun windowRowCarriesAnIconRatherThanNoArtwork() {
        // An absent artworkUri makes the car draw a music note on a per-row
        // colour; at 25-56 rows a tab that is a column of unrelated colours.
        val row = PlexMediaMapper.groupRowToMediaItem(
            "[albumWindowID]0", "A  -  B", R.drawable.ic_aa_albums
        )
        assertNotNull(row.mediaMetadata.artworkUri)
    }

    @Test
    fun windowRowNeverCarriesAStreamUri() {
        // A non-null localConfiguration would make resolveQueueForItem treat the
        // row as already resolved and "play" a row that has no stream.
        val row = PlexMediaMapper.groupRowToMediaItem(
            "[artistWindowID]0", "A  -  B", R.drawable.ic_aa_artists
        )
        assertNull(row.localConfiguration)
    }

    @Test
    fun `a group row carries its subtitle on the browse list's second line`() {
        // The same line an album uses for its artist. A letter row spends it on
        // the bucket's count, which is the only visible sign that a bucket
        // larger than the car's ~293-item ceiling is showing fewer artists than
        // it claims.
        val row = PlexMediaMapper.groupRowToMediaItem(
            "[artistLetterID]A", "A", R.drawable.ic_aa_artists, "79 artists"
        )

        assertEquals("A", row.mediaMetadata.title)
        assertEquals("79 artists", row.mediaMetadata.artist)
    }

    @Test
    fun `a group row with no subtitle leaves the second line empty`() {
        // Window rows pass none: every window holds WINDOW_SIZE items except the
        // last, so a count would say nothing.
        val row = PlexMediaMapper.groupRowToMediaItem(
            "[artistWindowID]0", "Beck  -  Cake", R.drawable.ic_aa_artists
        )

        assertNull(row.mediaMetadata.artist)
    }

    private companion object {
        /** A library scope as DecadeCompositeArt.scopeOf builds one: machine
         * identifier, then section key. Deliberately not a value any other
         * fixture here uses, so a mapper that dropped the argument could not
         * pass by accident. */
        const val SCOPE = "5cope1dent1f1er-7"
    }
}
