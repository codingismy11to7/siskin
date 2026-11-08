package com.cappielloantonio.tempo.service

import android.util.Log
import androidx.media3.common.MediaItem
import com.cappielloantonio.tempo.util.Preferences
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Extends a queue that has reached its last track: sonically similar tracks
 * first, random ones when that finds nothing new. Best effort -- every failure
 * is "nothing to add". See
 * docs/decisions/2026-11-08-continuous-play-on-player-design.md.
 */
class ContinuousPlay(
    private val similar: suspend (String) -> List<MediaItem>,
    private val random: suspend () -> List<MediaItem>,
    private val fallbackToRandom: () -> Boolean = Preferences::isFallbackToRandomTracksEnabled,
    private val usable: () -> Boolean = Preferences::isInstantMixUsable,
    private val markUsed: () -> Unit = Preferences::setLastInstantMix,
) {
    private val running = AtomicBoolean(false)

    /** False while a run is in flight or throttled; lets a caller skip launching one that would return at once. */
    fun ready(): Boolean = !running.get() && usable()

    /**
     * What to append after [seed], deduplicated against [queued] -- read after
     * each request, since the queue can change while one is in flight. The
     * similar tier may answer with tracks already queued, so it is filtered
     * before deciding whether the random tier is needed.
     *
     * Nothing, if [playing] has moved off [seed] by the time a request returns:
     * a new queue started meanwhile, and these were picked for the old one.
     */
    suspend fun tracksAfter(
        seed: MediaItem,
        playing: () -> MediaItem?,
        queued: () -> List<MediaItem>,
    ): List<MediaItem> {
        if (!usable() || !running.compareAndSet(false, true)) return emptyList()
        try {
            markUsed()
            val similarTracks = similar(seed.mediaId)
            if (moved(seed, playing())) return emptyList()
            val fromSimilar = newOnly("similar", similarTracks, queued())
            if (fromSimilar.isNotEmpty()) return fromSimilar
            if (!fallbackToRandom()) {
                Log.w(TAG, "no new similar tracks, random fallback disabled")
                return emptyList()
            }
            Log.w(TAG, "no new similar tracks, falling back to random")
            val randomTracks = random()
            if (moved(seed, playing())) return emptyList()
            return newOnly("random", randomTracks, queued())
        } finally {
            running.set(false)
        }
    }

    private fun moved(
        seed: MediaItem,
        playing: MediaItem?,
    ): Boolean = (playing?.mediaId != seed.mediaId).also { if (it) Log.i(TAG, "queue changed while fetching; dropping") }

    private fun newOnly(
        tier: String,
        fetched: List<MediaItem>,
        queued: List<MediaItem>,
    ): List<MediaItem> {
        val ids = queued.mapTo(HashSet()) { it.mediaId }
        val fresh = fetched.filter { it.mediaId !in ids }
        when {
            fetched.isEmpty() -> Log.w(TAG, "$tier tier returned nothing")
            fresh.isEmpty() -> Log.w(TAG, "all ${fetched.size} $tier tracks already queued")
            else -> Log.d(TAG, "adding ${fresh.size} of ${fetched.size} $tier tracks")
        }
        return fresh
    }

    companion object {
        private const val TAG = "ContinuousPlay"

        /** Tracks asked of each tier. */
        const val MIX_SIZE = 25
    }
}
