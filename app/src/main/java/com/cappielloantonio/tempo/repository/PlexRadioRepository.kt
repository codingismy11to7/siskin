package com.cappielloantonio.tempo.repository

import android.util.Log
import androidx.media3.common.MediaItem
import arrow.core.Either
import arrow.core.raise.either
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexMediaMapper
import com.cappielloantonio.tempo.plex.PlexMediaMapper.StationSeed
import com.cappielloantonio.tempo.plex.PlexTransportFailure
import com.cappielloantonio.tempo.plex.RatingKey
import com.cappielloantonio.tempo.plex.api.library.LibraryClient
import com.cappielloantonio.tempo.plex.base.PlexResponse

/**
 * Starts and extends Plex stations. A station is a play queue the
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
     * The new play queue, or an empty response when an artist has no station
     * -- which maps to no tracks, the same as an artist Mix with none. An
     * artist's key is fetched every time because the server mints a new one
     * per request; a library station's is stable and arrives with its row.
     * A mix is posted shuffled, so a redraw is a new order rather than album by album.
     */
    suspend fun startStation(seed: StationSeed): Either<PlexTransportFailure, PlexResponse> =
        either {
            val library = client()
            val stationKey =
                when (seed) {
                    is StationSeed.Library -> {
                        seed.key
                    }

                    is StationSeed.Mix -> {
                        seed.key
                    }

                    is StationSeed.Artist -> {
                        library
                            .getMetadataWithStations(RatingKey(seed.ratingKey))
                            .bind()
                            .mediaContainer
                            ?.metadata
                            ?.firstOrNull()
                            ?.stations
                            ?.metadata
                            ?.firstOrNull()
                            ?.key
                    }
                }
            if (stationKey == null) {
                Log.d(TAG, "$seed has no station")
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
            library.createPlayQueue(stationUri(machine, stationKey), shuffle = seed is StationSeed.Mix).bind()
        }

    /** The playable tracks of a station play-queue response, in server order. */
    fun stationTracks(
        response: PlexResponse,
        seed: StationSeed,
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
                PlexMediaMapper.StationTag(playQueueId, itemId, seed),
            )
        }
    }

    /**
     * Everything the station's queue now holds. A 404 means the server
     * discarded the queue -- it does after a while, which is how a restart
     * finds it -- and a finite station that has run out is equally finished, so
     * either way a fresh station from the same seed takes its place.
     */
    suspend fun extendStation(
        station: PlexMediaMapper.StationTag,
        lastQueuedItemId: Long,
        onLastTrack: Boolean,
    ): Either<PlexTransportFailure, List<MediaItem>> {
        val read = client().getPlayQueue(station.playQueueId)
        val finished =
            when (read) {
                is Either.Left -> read.value.let { it is PlexTransportFailure.Http && it.code == HTTP_NOT_FOUND }
                is Either.Right -> hasRunOut(read.value, lastQueuedItemId, onLastTrack)
            }
        if (finished) Log.d(TAG, "play queue ${station.playQueueId} is finished; drawing a new station")
        val response = if (finished) startStation(station.seed) else read
        return response.map { stationTracks(it, station.seed) }
    }

    companion object {
        private const val TAG = "PlexRadioRepository"
        private const val HTTP_NOT_FOUND = 404

        fun stationUri(
            machineIdentifier: String,
            stationKey: String,
        ) = "server://$machineIdentifier/com.plexapp.plugins.library$stationKey"

        /**
         * Item ids rather than mediaIds, because the window also holds played
         * items already trimmed from the player, and those look new by mediaId.
         *
         * [onLastTrack] ends the station wherever the window sits: the window
         * follows the server's selection, so under the car's shuffle the last
         * track can be far behind the last queued item, and a window around it
         * has nothing new with the station's end out of its reach. See
         * docs/decisions/2026-11-01-radio-stations-design.md.
         */
        fun hasRunOut(
            response: PlexResponse,
            lastQueuedItemId: Long,
            onLastTrack: Boolean,
        ): Boolean {
            val container = response.mediaContainer ?: return false
            val total = container.playQueueTotalCount ?: return false
            val items = container.metadata.orEmpty()
            if (items.any { (it.playQueueItemID ?: Long.MIN_VALUE) > lastQueuedItemId }) return false
            if (onLastTrack) return true
            val offset = container.playQueueSelectedItemOffset ?: return false
            val selected = items.indexOfFirst { it.playQueueItemID == container.playQueueSelectedItemID }
            return selected >= 0 && offset + items.size - selected >= total
        }
    }
}
