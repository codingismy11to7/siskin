package com.cappielloantonio.tempo.repository

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexItemType
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.plex.api.library.LibraryClient
import com.cappielloantonio.tempo.plex.base.PlexResponse
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

private const val TAG = "PlexMixRepository"

/**
 * Supplies tracks for continuous play: sonically similar first, random second.
 *
 * The similar tier needs Plex Pass sonic analysis. Without it the server
 * answers with an empty container rather than an error, so an empty result is
 * an ordinary outcome here and the caller falls through to [randomTracks].
 */
@OptIn(UnstableApi::class)
class PlexMixRepository {

    fun interface TracksCallback {
        fun onTracks(tracks: List<MediaItem>)
    }

    private val api = PlexApi()
    private val libraryClient = LibraryClient(api)

    private val serverUri: String? get() = api.serverUri
    private val token: String? get() = PlexApi.serverTokenOrAccount(api.serverToken, api.accountToken)

    fun similarTracks(ratingKey: String, count: Int, callback: TracksCallback) {
        enqueue(libraryClient.getSimilar(ratingKey, count), callback)
    }

    fun randomTracks(count: Int, callback: TracksCallback) {
        val key = api.musicSectionKey
        if (key == null) {
            Log.w(TAG, "no music section selected")
            callback.onTracks(emptyList())
            return
        }
        enqueue(
            libraryClient.getSectionContent(
                key, PlexItemType.TRACK, 0, count, LibraryClient.SORT_RANDOM
            ),
            callback
        )
    }

    /** Both tiers report failure as "no tracks" -- continuous play is best effort. */
    private fun enqueue(call: Call<PlexResponse>, callback: TracksCallback) {
        call.enqueue(object : Callback<PlexResponse> {
            override fun onResponse(call: Call<PlexResponse>, response: Response<PlexResponse>) {
                if (!response.isSuccessful) {
                    Log.w(TAG, "mix request failed with HTTP ${response.code()}")
                    callback.onTracks(emptyList())
                    return
                }
                callback.onTracks(
                    PlexBrowseRepository.tracksOf(response.body()).mapNotNull {
                        PlexMediaMapper.trackToMediaItem(it, null, serverUri, token)
                    }
                )
            }

            override fun onFailure(call: Call<PlexResponse>, t: Throwable) {
                Log.w(TAG, "mix request could not reach the server", t)
                callback.onTracks(emptyList())
            }
        })
    }
}
