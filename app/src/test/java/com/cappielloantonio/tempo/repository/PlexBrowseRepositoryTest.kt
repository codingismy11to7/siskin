package com.cappielloantonio.tempo.repository

import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionError
import com.cappielloantonio.tempo.plex.base.MediaContainer
import com.cappielloantonio.tempo.plex.base.PlexResponse
import com.cappielloantonio.tempo.plex.models.Metadata
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

// The resolved okhttp3 version only exposes ResponseBody.create(MediaType?, String)
// as deprecated in favour of an extension function this alpha release doesn't
// yet publish; the deprecated overload is otherwise exactly what's needed here.
@Suppress("DEPRECATION")
class PlexBrowseRepositoryTest {

    private fun response(vararg items: Metadata) = PlexResponse().apply {
        mediaContainer = MediaContainer().apply { metadata = items.toList() }
    }

    private fun item(ratingKey: String?, type: String) = Metadata().apply {
        this.ratingKey = ratingKey
        this.type = type
    }

    /**
     * A stand-in for the real `getPlaylists`/`getArtists`/... map lambdas:
     * narrow with [PlexBrowseRepository.tracksOf] like a real caller would,
     * then blow up on anything that survives the narrowing. `resultFor`
     * always invokes this on a successful response, even when the eventual
     * list is empty -- only the per-item builder inside it is skipped, so a
     * lambda that explodes unconditionally would fail against the real
     * production lambdas too and would prove nothing.
     */
    private val trackMapThatMustNotBuildAnyItem: (PlexResponse?) -> List<MediaItem> =
        { body -> PlexBrowseRepository.tracksOf(body).map { error("built a MediaItem from $it, but the narrowed list should have been empty") } }

    /** For the error branch, where `resultFor` must not call `map` at all. */
    private val mapThatMustNotRun: (PlexResponse?) -> List<MediaItem> =
        { error("map must not run on a non-2xx response") }

    @Test
    fun tracksOfKeepsOnlyTracks() {
        // A playlist or album listing can carry non-track entries; anything the
        // player cannot stream must not reach the queue.
        val tracks = PlexBrowseRepository.tracksOf(
            response(item("1", "track"), item("2", "album"), item("3", "track"))
        )
        assertEquals(listOf("1", "3"), tracks.map { it.ratingKey })
    }

    @Test
    fun tracksOfDropsEntriesWithoutARatingKey() {
        val tracks = PlexBrowseRepository.tracksOf(
            response(item(null, "track"), item("", "track"), item("3", "track"))
        )
        assertEquals(listOf("3"), tracks.map { it.ratingKey })
    }

    @Test
    fun tracksOfReturnsEmptyForAnAbsentOrEmptyContainer() {
        // A Plex library with no matching items answers 200 with an absent
        // Metadata list. That is an empty tab, not an error -- the Subsonic
        // implementation got this wrong for playlists and showed "Something
        // went wrong" on the first tab for anyone with no playlists.
        assertTrue(PlexBrowseRepository.tracksOf(null).isEmpty())
        assertTrue(PlexBrowseRepository.tracksOf(PlexResponse()).isEmpty())
        assertTrue(PlexBrowseRepository.tracksOf(response()).isEmpty())
    }

    @Test
    fun itemsOfNarrowsToTheRequestedType() {
        val albums = PlexBrowseRepository.itemsOf(
            response(item("1", "album"), item("2", "artist"), item("3", "album")),
            "album"
        )
        assertEquals(listOf("1", "3"), albums.map { it.ratingKey })
    }

    @Test
    fun itemsOfReturnsEmptyForAnAbsentContainer() {
        assertTrue(PlexBrowseRepository.itemsOf(null, "album").isEmpty())
        assertTrue(PlexBrowseRepository.itemsOf(PlexResponse(), "album").isEmpty())
    }

    // ── resultFor: the fetch() response→LibraryResult decision ─────────

    @Test
    fun resultForAnEmptyLibrarySucceedsWithAnEmptyListRatherThanErroring() {
        // The regression this whole task exists to catch: Plex answers a
        // no-match listing with HTTP 200, MediaContainer present, Metadata
        // absent. The Subsonic implementation this replaces mistook that shape
        // for a failure and showed "Something went wrong" on the first of
        // three browse tabs for every user with no playlists.
        val response = Response.success(PlexResponse().apply { mediaContainer = MediaContainer() })

        val result = PlexBrowseRepository.resultFor(response, trackMapThatMustNotBuildAnyItem)

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        assertTrue(result.value!!.isEmpty())
    }

    @Test
    fun resultForANullBodySucceedsWithAnEmptyList() {
        // Plex is not documented to send a 200 with no body at all, but the
        // code never distinguishes it from an absent Metadata list -- both
        // reach tracksOf/itemsOf as null and degrade to emptyList() there
        // (see tracksOfReturnsEmptyForAnAbsentOrEmptyContainer). Pinning the
        // real behaviour: this still succeeds with an empty list rather than
        // throwing or erroring.
        val response = Response.success<PlexResponse>(null)

        val result = PlexBrowseRepository.resultFor(response, trackMapThatMustNotBuildAnyItem)

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        assertTrue(result.value!!.isEmpty())
    }

    @Test
    fun resultForHttp401IsPermissionDenied() {
        // 401/403 must map to ERROR_PERMISSION_DENIED specifically: a later
        // task keys the "sign in again" affordance off exactly that code.
        val response = Response.error<PlexResponse>(401, ResponseBody.create(null, ""))

        val result = PlexBrowseRepository.resultFor(response, mapThatMustNotRun)

        assertEquals(SessionError.ERROR_PERMISSION_DENIED, result.resultCode)
    }

    @Test
    fun resultForHttp403IsPermissionDenied() {
        val response = Response.error<PlexResponse>(403, ResponseBody.create(null, ""))

        val result = PlexBrowseRepository.resultFor(response, mapThatMustNotRun)

        assertEquals(SessionError.ERROR_PERMISSION_DENIED, result.resultCode)
    }

    @Test
    fun resultForHttp500IsBadValue() {
        // Any other non-2xx code must not collapse into ERROR_PERMISSION_DENIED.
        val response = Response.error<PlexResponse>(500, ResponseBody.create(null, ""))

        val result = PlexBrowseRepository.resultFor(response, mapThatMustNotRun)

        assertEquals(SessionError.ERROR_BAD_VALUE, result.resultCode)
    }
}
