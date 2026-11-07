package com.cappielloantonio.tempo.service.crossfade

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG = "CrossfadeRamps"

/** Main-thread only: [request] and [infoFor] are called from the player's thread. */
class CrossfadeRamps(
    private val scope: CoroutineScope,
    private val session: () -> Any?,
    private val fetch: suspend (mediaId: String) -> TrackFadeInfo?,
) {
    private var cachedFor: Any? = null
    private val infos = HashMap<String, TrackFadeInfo>()
    private val pending = HashSet<String>()

    fun infoFor(mediaId: String): TrackFadeInfo? {
        forgetIfSessionChanged()
        return infos[mediaId]
    }

    fun request(mediaId: String) {
        forgetIfSessionChanged()
        if (mediaId in infos || !pending.add(mediaId)) return
        val askedFor = cachedFor
        scope.launch {
            // Outside any either { }: a fetch failure must never reach playback.
            val info =
                try {
                    fetch(mediaId)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "ramp fetch failed for $mediaId: $t")
                    null
                }
            if (session() != askedFor) return@launch
            pending.remove(mediaId)
            infos[mediaId] = info ?: TrackFadeInfo(null, null)
        }
    }

    private fun forgetIfSessionChanged() {
        val current = session()
        if (current != cachedFor) {
            cachedFor = current
            infos.clear()
            pending.clear()
        }
    }
}
