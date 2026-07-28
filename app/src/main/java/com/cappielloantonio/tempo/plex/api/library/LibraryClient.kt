package com.cappielloantonio.tempo.plex.api.library

import android.util.Log
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexRetrofitFactory
import com.cappielloantonio.tempo.plex.base.PlexResponse
import com.cappielloantonio.tempo.plex.models.Directory

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

    suspend fun getSections(): PlexResponse {
        Log.d(TAG, "getSections()")
        return service.getSections()
    }

    suspend fun getSectionContent(
        sectionKey: String,
        type: Int,
        start: Int,
        size: Int,
        sort: String? = null
    ): PlexResponse {
        Log.d(TAG, "getSectionContent($sectionKey, type=$type, start=$start, size=$size, sort=$sort)")
        return service.getSectionContent(sectionKey, type, start, size, sort)
    }

    suspend fun getChildren(ratingKey: String, start: Int, size: Int): PlexResponse =
        service.getChildren(ratingKey, start, size)

    suspend fun getSimilar(ratingKey: String, limit: Int): PlexResponse {
        Log.d(TAG, "getSimilar($ratingKey, limit=$limit)")
        return service.getSimilar(ratingKey, limit)
    }

    suspend fun getMetadata(ratingKey: String): PlexResponse = service.getMetadata(ratingKey)

    suspend fun getSectionHubs(sectionKey: String): PlexResponse = service.getSectionHubs(sectionKey)

    companion object {
        /** Plex reports a music library section's type as "artist". */
        private const val MUSIC_SECTION_TYPE = "artist"

        /** Sorts an album listing by artist, for the "view by albums" tab. */
        const val SORT_ARTIST = "artist.titleSort"

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
