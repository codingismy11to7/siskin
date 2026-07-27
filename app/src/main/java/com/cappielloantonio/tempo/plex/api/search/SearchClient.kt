package com.cappielloantonio.tempo.plex.api.search

import android.util.Log
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexRetrofitFactory
import com.cappielloantonio.tempo.plex.base.PlexResponse
import com.cappielloantonio.tempo.plex.models.Metadata
import retrofit2.Call

private const val TAG = "SearchClient"

/**
 * Search, playlists, and reporting playback position back to Plex.
 *
 * Captures `api.serverUri` at construction time, via [PlexRetrofitFactory.server].
 * It does not observe later changes -- discard and reconstruct this client
 * whenever the server changes.
 */
class SearchClient(api: PlexApi) {

    private val service: SearchService =
        PlexRetrofitFactory.server(api).create(SearchService::class.java)

    fun search(sectionKey: String, query: String, limit: Int = DEFAULT_SEARCH_LIMIT): Call<PlexResponse> {
        Log.d(TAG, "search($sectionKey, limit=$limit)")
        return service.search(sectionKey, query, limit)
    }

    fun getPlaylists(): Call<PlexResponse> = service.getPlaylists()

    fun getPlaylistItems(playlistId: String): Call<PlexResponse> =
        service.getPlaylistItems(playlistId)

    fun reportProgress(ratingKey: String, key: String, state: String, timeMs: Long): Call<Void> =
        service.reportProgress(ratingKey, key, state, timeMs)

    companion object {
        private const val DEFAULT_SEARCH_LIMIT = 50

        const val STATE_PLAYING = "playing"
        const val STATE_PAUSED = "paused"
        const val STATE_STOPPED = "stopped"

        private val PLAYABLE_TYPES = setOf("track", "album", "artist")

        /**
         * Narrows a result set to what this app can present. Even a section-scoped
         * search returns clips and other types, and a result without a ratingKey
         * cannot be browsed or played.
         */
        @JvmStatic
        fun playableResults(response: PlexResponse?): List<Metadata> =
            response?.mediaContainer?.metadata
                ?.filter { it.type in PLAYABLE_TYPES && !it.ratingKey.isNullOrBlank() }
                ?: emptyList()
    }
}
