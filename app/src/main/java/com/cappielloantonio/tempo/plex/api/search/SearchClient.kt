package com.cappielloantonio.tempo.plex.api.search

import android.util.Log
import arrow.core.Either
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexHost
import com.cappielloantonio.tempo.plex.PlexRetrofitFactory
import com.cappielloantonio.tempo.plex.PlexTransportFailure
import com.cappielloantonio.tempo.plex.RatingKey
import com.cappielloantonio.tempo.plex.SectionKey
import com.cappielloantonio.tempo.plex.base.PlexResponse
import com.cappielloantonio.tempo.plex.plexCall

private const val TAG = "SearchClient"

/**
 * Search, playlists, and reporting playback position back to Plex.
 *
 * Pinned to the [serverUri] it was constructed with and never re-reads it --
 * discard and reconstruct whenever the server changes. Taking the address as a
 * parameter is what lets sign-in read a candidate server's sections before it
 * has committed a [com.cappielloantonio.tempo.plex.PlexSession].
 */
class SearchClient(
    api: PlexApi,
    serverUri: String?,
    serverToken: String?,
) {
    /** Uses whatever server the persisted session names. */
    constructor(api: PlexApi) : this(api, api.serverUri, api.serverToken)

    private val service: SearchService =
        PlexRetrofitFactory.server(api, serverUri, serverToken).create(SearchService::class.java)

    /**
     * [type] is a PlexItemType value and is not optional: Plex rejects a search
     * without it. Callers wanting artists, albums and tracks must issue three
     * searches and merge -- Plex accepts only one type per request.
     */
    suspend fun search(
        sectionKey: SectionKey,
        query: String,
        type: Int,
        limit: Int = DEFAULT_SEARCH_LIMIT,
    ): Either<PlexTransportFailure, PlexResponse> {
        Log.d(TAG, "search($sectionKey, type=$type, limit=$limit)")
        return plexCall(PlexHost.Server) { service.search(sectionKey.value, query, type, limit) }
    }

    /** [sectionKey] scopes the listing to one music library section -- see SearchService.getPlaylists. */
    suspend fun getPlaylists(sectionKey: SectionKey): Either<PlexTransportFailure, PlexResponse> =
        plexCall(PlexHost.Server) { service.getPlaylists(sectionKey.value) }

    suspend fun getPlaylistItems(
        playlistId: RatingKey,
        start: Int,
        size: Int,
    ): Either<PlexTransportFailure, PlexResponse> = plexCall(PlexHost.Server) { service.getPlaylistItems(playlistId.value, start, size) }

    suspend fun reportProgress(
        ratingKey: RatingKey,
        key: String,
        state: String,
        timeMs: Long,
    ): Either<PlexTransportFailure, Unit> = plexCall(PlexHost.Server) { service.reportProgress(ratingKey.value, key, state, timeMs) }

    /**
     * A `Right` is the success case: the service call returns Unit and any
     * non-2xx becomes [PlexTransportFailure.Http], so there is no body to inspect.
     */
    suspend fun rate(
        ratingKey: RatingKey,
        rating: Int,
    ): Either<PlexTransportFailure, Unit> {
        Log.d(TAG, "rate($ratingKey, rating=$rating)")
        return plexCall(PlexHost.Server) { service.rate(ratingKey.value, LIBRARY_IDENTIFIER, rating) }
    }

    companion object {
        private const val DEFAULT_SEARCH_LIMIT = 50

        private const val LIBRARY_IDENTIFIER = "com.plexapp.plugins.library"

        const val STATE_PLAYING = "playing"
        const val STATE_PAUSED = "paused"
        const val STATE_STOPPED = "stopped"

        /**
         * Plex's 0-10 scale: 10 is five stars.
         *
         * Verified against a live PMS 1.43.3 server: `rating=10` sets
         * `userRating` to `10.0`. `rating=0` was tried as the "cleared" value
         * and does **not** clear it -- it sets `userRating` to `0.0`, a real
         * zero-star rating, which is not the same as absent. `rating=-1` is
         * what actually clears `userRating` back to null/absent, which is why
         * it is used here rather than the more obvious `0`.
         */
        const val RATING_HEARTED = 10
        const val RATING_CLEARED = -1
    }
}
