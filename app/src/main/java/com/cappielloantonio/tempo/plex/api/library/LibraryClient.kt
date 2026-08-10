package com.cappielloantonio.tempo.plex.api.library

import android.util.Log
import arrow.core.Either
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexHost
import com.cappielloantonio.tempo.plex.PlexItemType
import com.cappielloantonio.tempo.plex.PlexRetrofitFactory
import com.cappielloantonio.tempo.plex.PlexTransportFailure
import com.cappielloantonio.tempo.plex.RatingKey
import com.cappielloantonio.tempo.plex.SectionKey
import com.cappielloantonio.tempo.plex.base.PlexResponse
import com.cappielloantonio.tempo.plex.models.Directory
import com.cappielloantonio.tempo.plex.plexCall

private const val TAG = "LibraryClient"

/**
 * Browsing a Plex music library: sections, their contents, and hub rows.
 *
 * Pinned to the [serverUri] it was constructed with and never re-reads it --
 * discard and reconstruct whenever the server changes. Taking the address as a
 * parameter is what lets sign-in read a candidate server's sections before it
 * has committed a [com.cappielloantonio.tempo.plex.PlexSession].
 */
class LibraryClient(api: PlexApi, serverUri: String?, serverToken: String?) {

    /** Uses whatever server the persisted session names. */
    constructor(api: PlexApi) : this(api, api.serverUri, api.serverToken)

    private val service: LibraryService =
        PlexRetrofitFactory.server(api, serverUri, serverToken).create(LibraryService::class.java)

    suspend fun getSections(): Either<PlexTransportFailure, PlexResponse> {
        Log.d(TAG, "getSections()")
        return plexCall(PlexHost.Server) { service.getSections() }
    }

    suspend fun getSectionContent(
        sectionKey: SectionKey,
        type: Int,
        start: Int,
        size: Int,
        sort: String? = null,
        artistId: String? = null,
        trackDecade: String? = null,
        albumDecade: String? = null
    ): Either<PlexTransportFailure, PlexResponse> {
        Log.d(
            TAG,
            "getSectionContent($sectionKey, type=$type, start=$start, size=$size, " +
                "sort=$sort, artistId=$artistId, trackDecade=$trackDecade, " +
                "albumDecade=$albumDecade)"
        )
        return plexCall(PlexHost.Server) {
            service.getSectionContent(
                sectionKey.value, type, start, size, sort, artistId, trackDecade, albumDecade
            )
        }
    }

    suspend fun getChildren(
        ratingKey: RatingKey,
        start: Int,
        size: Int
    ): Either<PlexTransportFailure, PlexResponse> =
        plexCall(PlexHost.Server) { service.getChildren(ratingKey.value, start, size) }

    suspend fun getNearest(ratingKey: RatingKey, limit: Int): Either<PlexTransportFailure, PlexResponse> {
        Log.d(TAG, "getNearest($ratingKey, limit=$limit)")
        return plexCall(PlexHost.Server) { service.getNearest(ratingKey.value, limit) }
    }

    suspend fun getMetadata(ratingKey: RatingKey): Either<PlexTransportFailure, PlexResponse> =
        plexCall(PlexHost.Server) { service.getMetadata(ratingKey.value) }

    suspend fun getSectionHubs(sectionKey: SectionKey): Either<PlexTransportFailure, PlexResponse> =
        plexCall(PlexHost.Server) { service.getSectionHubs(sectionKey.value) }

    /**
     * The decades this section's albums fall into. Always asks for
     * [PlexItemType.ALBUM] -- see [LibraryService.getDecades] for why no other
     * type has an answer.
     */
    suspend fun getDecades(sectionKey: SectionKey): Either<PlexTransportFailure, PlexResponse> {
        Log.d(TAG, "getDecades($sectionKey)")
        return plexCall(PlexHost.Server) {
            service.getDecades(sectionKey.value, PlexItemType.ALBUM)
        }
    }

    companion object {
        /** Plex reports a music library section's type as "artist". */
        private const val MUSIC_SECTION_TYPE = "artist"

        /**
         * Sorts by the name actually shown, rather than by Plex's sort title.
         *
         * `titleSort` is what [SORT_TITLE] and the server default order by, and
         * in a car it files things where nobody will look for them. Measured on
         * a live library of 1204 artists: 521 carry an explicit `titleSort`, and
         * they are not all the tidy "The Beatles" -> "Beatles" case -- "Max
         * Graham" sorts as "Deep Funk Project" and lands at index 292, among the
         * Ds, while "The Hilliard Ensemble" sorts as "[anonymous]" and comes
         * first in the whole library. Under `sort=title` the same artist lands
         * at 645, under M.
         *
         * This matters more since the browse tree windows a long list into
         * labelled ranges: a window is named after the item at its edge, so an
         * ordering the names disagree with produces ranges that read as broken.
         * Ordering by the displayed name is what keeps a label and its contents
         * the same thing.
         */
        const val SORT_DISPLAY_TITLE = "title"

        /**
         * Server-side shuffle, for continuous play's random tier and for the
         * decade track fetch.
         */
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
