package com.cappielloantonio.tempo.plex.api.library

import android.util.Log
import arrow.core.Either
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexFailure
import com.cappielloantonio.tempo.plex.PlexHost
import com.cappielloantonio.tempo.plex.PlexRetrofitFactory
import com.cappielloantonio.tempo.plex.base.PlexResponse
import com.cappielloantonio.tempo.plex.models.Directory
import com.cappielloantonio.tempo.plex.plexCall

private const val TAG = "LibraryClient"

/**
 * Browsing a Plex music library: sections, their contents, and hub rows.
 *
 * Captures `api.serverUri` at construction time, via [PlexRetrofitFactory.server].
 * It does not observe later changes -- discard and reconstruct this client
 * whenever the server changes.
 */
class LibraryClient(api: PlexApi) {

    private val service: LibraryService =
        PlexRetrofitFactory.server(api).create(LibraryService::class.java)

    suspend fun getSections(): Either<PlexFailure, PlexResponse> {
        Log.d(TAG, "getSections()")
        return plexCall(PlexHost.Server) { service.getSections() }
    }

    suspend fun getSectionContent(
        sectionKey: String,
        type: Int,
        start: Int,
        size: Int,
        sort: String? = null
    ): Either<PlexFailure, PlexResponse> {
        Log.d(TAG, "getSectionContent($sectionKey, type=$type, start=$start, size=$size, sort=$sort)")
        return plexCall(PlexHost.Server) {
            service.getSectionContent(sectionKey, type, start, size, sort)
        }
    }

    suspend fun getChildren(
        ratingKey: String,
        start: Int,
        size: Int
    ): Either<PlexFailure, PlexResponse> =
        plexCall(PlexHost.Server) { service.getChildren(ratingKey, start, size) }

    suspend fun getNearest(ratingKey: String, limit: Int): Either<PlexFailure, PlexResponse> {
        Log.d(TAG, "getNearest($ratingKey, limit=$limit)")
        return plexCall(PlexHost.Server) { service.getNearest(ratingKey, limit) }
    }

    suspend fun getMetadata(ratingKey: String): Either<PlexFailure, PlexResponse> =
        plexCall(PlexHost.Server) { service.getMetadata(ratingKey) }

    suspend fun getSectionHubs(sectionKey: String): Either<PlexFailure, PlexResponse> =
        plexCall(PlexHost.Server) { service.getSectionHubs(sectionKey) }

    companion object {
        /** Plex reports a music library section's type as "artist". */
        private const val MUSIC_SECTION_TYPE = "artist"

        /**
         * Sorts an album listing by artist, for the "view by albums" tab.
         *
         * Verified against a live PMS 1.43.3 server: `artist.titleSort` returns
         * albums ordered by artist, where plain `titleSort` (see [SORT_TITLE])
         * orders by album title instead.
         */
        const val SORT_ARTIST = "artist.titleSort"

        /**
         * Sorts an album listing by album title, for the Albums tab.
         *
         * Passed explicitly rather than left to the server default, which is
         * already artist order -- without this the Albums tab and the "view by
         * albums" entry render the same list and one of the two is pointless.
         */
        const val SORT_TITLE = "titleSort"

        /** Server-side shuffle, for continuous play's random tier. */
        const val SORT_RANDOM = "random"

        /**
         * Narrows a sections listing to the music libraries. An account commonly
         * has movie and TV sections this app cannot play.
         */
        @JvmStatic
        fun musicSections(response: PlexResponse?): List<Directory> =
            response?.mediaContainer?.directory
                ?.filter { it.type == MUSIC_SECTION_TYPE && !it.key.isNullOrBlank() }
                ?: emptyList()
    }
}
