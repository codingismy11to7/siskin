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

    /**
     * The first-character buckets this section's artists fall into. Always asks
     * for [PlexItemType.ARTIST]: the Albums tab is not offered this shape,
     * because album buckets B=350 and S=315 are both over the car's ~281-item
     * ceiling.
     */
    suspend fun getFirstCharacters(sectionKey: SectionKey): Either<PlexTransportFailure, PlexResponse> {
        Log.d(TAG, "getFirstCharacters($sectionKey)")
        return plexCall(PlexHost.Server) {
            service.getFirstCharacters(sectionKey.value, PlexItemType.ARTIST)
        }
    }

    /**
     * One bucket's artists. [key] must be passed exactly as the index gave it,
     * percent-encoding included -- see [LibraryService.getFirstCharacterContent].
     */
    suspend fun getFirstCharacterContent(
        sectionKey: SectionKey,
        key: String,
        start: Int,
        size: Int
    ): Either<PlexTransportFailure, PlexResponse> {
        Log.d(TAG, "getFirstCharacterContent($sectionKey, key=$key, start=$start, size=$size)")
        return plexCall(PlexHost.Server) {
            service.getFirstCharacterContent(
                sectionKey.value, key, PlexItemType.ARTIST, start, size
            )
        }
    }

    /**
     * One hub's contents, or null when its key is not safe to follow.
     *
     * Null rather than a Left: a rejected key is not a transport failure, it is
     * a row that should never have been drawn, and [PlexBrowseRepository]
     * drops it at listing time.
     */
    suspend fun getByHubKey(key: String): Either<PlexTransportFailure, PlexResponse>? {
        if (!isSafeHubKey(key)) {
            Log.w(TAG, "refusing to follow a hub key that is not a relative path")
            return null
        }
        Log.d(TAG, "getByHubKey($key)")
        return plexCall(PlexHost.Server) { service.getByPath(key) }
    }

    companion object {
        /** Plex reports a music library section's type as "artist". */
        private const val MUSIC_SECTION_TYPE = "artist"

        /**
         * Whether a hub's key may be followed.
         *
         * The token rides on every request this client makes, so an absolute
         * URL out of a response body would hand a full account credential to
         * whatever host it named. A single leading slash is most of the rule:
         * "//host/path" is protocol-relative and resolves off-host, so it is
         * rejected along with "https://...".
         *
         * The backslash check is not decorative. OkHttp's `HttpUrl.resolve`
         * follows the WHATWG URL Standard's backslash-as-slash normalisation,
         * so a key starting "/\\" or "\\\\" resolves exactly like "//" --
         * measured: resolving "/\\evil.example/x" against this server's base
         * URL answers `http://evil.example/x`, a different host, even though
         * that string passes `startsWith("/")` and fails `startsWith("//")`.
         * A real Plex hub key is a server-generated section path and never
         * contains a literal backslash, so any is rejected outright rather
         * than trying to enumerate the positions where OkHttp treats one as
         * a slash.
         */
        @JvmStatic
        fun isSafeHubKey(key: String?): Boolean =
            key != null && key.startsWith("/") && !key.startsWith("//") && !key.contains('\\')

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
         *
         * This describes one of the Artists tab's two shapes. The
         * first-character buckets send **no** sort at all, deliberately: their
         * membership is decided by `titleSort`, so ordering their contents by
         * the displayed name scrambles them -- bucket D under `sort=title` opens
         * on "Arne Domnérus, Bob Dylan, Brigitte DeMeyer". A window has an edge
         * item to be named after and a bucket does not, which is why the two
         * disagree. See
         * docs/decisions/2026-08-10-artists-by-initial-design.md.
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
