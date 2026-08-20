package com.cappielloantonio.tempo.provider

import android.content.Context
import android.util.Log
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexItemType
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.api.library.LibraryClient
import com.cappielloantonio.tempo.plex.base.PlexResponse
import com.cappielloantonio.tempo.repository.PlexBrowseRepository
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * The 2x2 cover mosaic behind a decade row: the Plex query that finds its
 * covers, over [CompositeArt]'s cache and drawing.
 *
 * Plex has no composite for a filter value -- measured against PMS 1.43.3, the
 * decade index returns Directory entries with no thumb, composite or art;
 * `/library/sections/{key}/decade/{decade}/composite` 404s; and
 * `/library/sections/{key}/composite/1` is a section-wide mosaic identical for
 * every decade. So the tiling is ours.
 *
 * Kotlin rather than a method on AlbumArtContentProvider because LibraryClient
 * exposes suspend functions and that provider is Java -- the same wall
 * PlexScrobbler exists to cross for MediaManager.java. It also puts the whole
 * feature behind one seam a test can drive without standing up a ContentProvider.
 */
object DecadeCompositeArt {
    private const val TAG = "DecadeCompositeArt"

    private const val TYPE_ALBUM = "album"

    /**
     * The thumb paths to tile, in server order.
     *
     * Filters thumb-less albums rather than leaving a hole, which is what the
     * [CompositeGrid.OVER_FETCH] on the request is for. Reuses
     * [PlexMediaMapper.artworkThumb] rather than reading `thumb` directly, so an
     * album that carries only a parent thumb still contributes a cover.
     */
    @JvmStatic
    fun coverThumbs(
        response: PlexResponse?,
        want: Int,
    ): List<String> =
        PlexBrowseRepository
            .itemsOf(response, TYPE_ALBUM)
            .mapNotNull { PlexMediaMapper.artworkThumb(it) }
            .take(want)

    /** @see CompositeArt.cached */
    @JvmStatic
    fun cached(
        context: Context,
        decade: String,
        bucket: Long,
    ): File? = CompositeArt.cached(context, decade, bucket)

    /**
     * The decade's covers, fetched.
     *
     * Pinned to the session snapshot [CompositeArt.build] took, not the
     * one-argument LibraryClient(api) constructor: that reads
     * api.serverUri/serverToken fresh from preferences, which can race a
     * library switch on More -> Server Select and pair this section key with a
     * different server's address mid-build.
     *
     * Runs inside the build lock, which is what turns N concurrent opens of one
     * missing tile into one Plex query rather than N.
     *
     * A 401 here deliberately does not raise the sign-in affordance: a
     * ContentProvider has no route to MediaLibrarySessionCallback's
     * PendingIntent, and needs none, because the browse call that produced the
     * list being drawn would have hit the same 401 first and raised it there.
     */
    private fun fetchThumbs(
        api: PlexApi,
        session: PlexSession,
        decade: String,
    ): List<String> {
        val response =
            try {
                runBlocking {
                    LibraryClient(api, session.serverUri, session.serverToken).getSectionContent(
                        sectionKey = session.musicSectionKey,
                        type = PlexItemType.ALBUM,
                        start = 0,
                        size = CompositeGrid.OVER_FETCH,
                        sort = LibraryClient.SORT_RANDOM,
                        albumDecade = decade,
                    )
                }.getOrNull()
            } catch (e: Exception) {
                // plexCall catches only IOException/HttpException; a malformed
                // response body (Gson JsonSyntaxException) or a mapping bug is a
                // RuntimeException that would otherwise escape build() and break its
                // contract that every failure is a null, never a throw. Outside any
                // either { } block, so there is no Arrow raise here to swallow.
                Log.w(TAG, "could not fetch section content for $decade", e)
                null
            } ?: return emptyList()

        return coverThumbs(response, CompositeGrid.OVER_FETCH)
    }

    /** @see CompositeArt.build */
    @JvmStatic
    fun build(
        context: Context,
        decade: String,
        bucket: Long,
    ): File? =
        CompositeArt.build(context, decade, bucket) { api, session ->
            fetchThumbs(api, session, decade)
        }
}
