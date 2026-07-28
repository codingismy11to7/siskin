package com.cappielloantonio.tempo.plex.api.library

import android.util.Log
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexRetrofitFactory
import com.cappielloantonio.tempo.plex.base.PlexResponse
import com.cappielloantonio.tempo.plex.models.Directory
import retrofit2.Call

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

    fun getSections(): Call<PlexResponse> {
        Log.d(TAG, "getSections()")
        return service.getSections()
    }

    fun getSectionContent(
        sectionKey: String,
        type: Int,
        start: Int,
        size: Int
    ): Call<PlexResponse> {
        Log.d(TAG, "getSectionContent($sectionKey, type=$type, start=$start, size=$size)")
        return service.getSectionContent(sectionKey, type, start, size)
    }

    fun getChildren(ratingKey: String, start: Int, size: Int): Call<PlexResponse> =
        service.getChildren(ratingKey, start, size)

    fun getMetadata(ratingKey: String): Call<PlexResponse> = service.getMetadata(ratingKey)

    fun getSectionHubs(sectionKey: String): Call<PlexResponse> = service.getSectionHubs(sectionKey)

    companion object {
        /** Plex reports a music library section's type as "artist". */
        private const val MUSIC_SECTION_TYPE = "artist"

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
