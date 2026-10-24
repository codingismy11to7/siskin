package com.cappielloantonio.tempo.repository

import android.util.Log
import androidx.media3.common.MediaItem
import arrow.core.Either
import arrow.core.raise.either
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.plex.PlexTransportFailure
import com.cappielloantonio.tempo.plex.RatingKey
import com.cappielloantonio.tempo.plex.api.library.LibraryClient
import com.cappielloantonio.tempo.plex.base.PlexResponse

/**
 * Starts and extends Plex artist stations. A station is a play queue the
 * server owns and grows; see docs/decisions/2026-10-23-artist-radio-design.md
 * for what it was measured to do.
 *
 * [client] is a provider rather than a client so this follows the session the
 * way [PlexBrowseRepository] does: a client is pinned to the server it was
 * built for.
 */
class PlexRadioRepository(
    private val api: PlexApi = PlexApi(),
    private val client: () -> LibraryClient = { LibraryClient(api) },
) {
    /**
     * The new play queue, or an empty response when the artist has no
     * station -- which maps to no tracks, the same as an artist Mix with none.
     * The station key is fetched every time because the server mints a new one
     * per request.
     */
    suspend fun startStation(artistRatingKey: String): Either<PlexTransportFailure, PlexResponse> =
        either {
            val library = client()
            val artist = library.getMetadataWithStations(RatingKey(artistRatingKey)).bind()
            val stationKey =
                artist.mediaContainer
                    ?.metadata
                    ?.firstOrNull()
                    ?.stations
                    ?.metadata
                    ?.firstOrNull()
                    ?.key
            if (stationKey == null) {
                Log.d(TAG, "artist $artistRatingKey has no station")
                return@either PlexResponse()
            }
            val machine =
                api.session?.machineIdentifier
                    ?: library
                        .getIdentity()
                        .bind()
                        .mediaContainer
                        ?.machineIdentifier
            if (machine == null) {
                Log.w(TAG, "server did not say who it is; cannot name the station")
                return@either PlexResponse()
            }
            library.createPlayQueue(stationUri(machine, stationKey)).bind()
        }

    /** The playable tracks of a station play-queue response, in server order. */
    fun stationTracks(
        response: PlexResponse,
        artistRatingKey: String,
    ): List<MediaItem> {
        val playQueueId = response.mediaContainer?.playQueueID ?: return emptyList()
        val serverUri = api.session?.serverUri
        val token = api.session?.serverAuthToken
        return PlexBrowseRepository.tracksOf(response).mapNotNull { track ->
            val itemId = track.playQueueItemID ?: return@mapNotNull null
            PlexMediaMapper.trackToMediaItem(
                track,
                null,
                serverUri,
                token,
                PlexMediaMapper.StationTag(playQueueId, itemId, artistRatingKey),
            )
        }
    }

    /**
     * Everything the station's queue now holds. A 404 means the server
     * discarded the queue -- it does after a while, which is how a restart
     * finds it -- so a fresh station for the same artist takes its place.
     */
    suspend fun extendStation(station: PlexMediaMapper.StationTag): Either<PlexTransportFailure, List<MediaItem>> {
        val read = client().getPlayQueue(station.playQueueId)
        val response =
            if ((read as? Either.Left)?.value.let { it is PlexTransportFailure.Http && it.code == HTTP_NOT_FOUND }) {
                Log.d(TAG, "play queue ${station.playQueueId} is gone; drawing a new station")
                startStation(station.artistRatingKey)
            } else {
                read
            }
        return response.map { stationTracks(it, station.artistRatingKey) }
    }

    companion object {
        private const val TAG = "PlexRadioRepository"
        private const val HTTP_NOT_FOUND = 404

        fun stationUri(
            machineIdentifier: String,
            stationKey: String,
        ) = "server://$machineIdentifier/com.plexapp.plugins.library$stationKey"
    }
}
