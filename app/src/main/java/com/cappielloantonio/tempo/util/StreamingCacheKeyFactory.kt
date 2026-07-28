package com.cappielloantonio.tempo.util

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheKeyFactory

/**
 * Cache key for the streaming cache. dataSpec.key, when a caller sets it,
 * identifies the audio bytes directly; nothing currently does, so this falls
 * through to the request URL.
 *
 * A prior version parsed Subsonic's `id`/`maxBitRate`/`format`/`timeOffset`
 * query parameters and a stored server id so cache entries survived token and
 * server-address churn. Plex stream URLs (`MediaUrlBuilder.streamUrl`) carry
 * none of those parameters -- only a `partKey` path and `X-Plex-Token` -- so
 * that lookup always missed and fell back to the full URL anyway. Removed
 * with the rest of the Subsonic-only preferences it read.
 */
@UnstableApi
class StreamingCacheKeyFactory : CacheKeyFactory {
    override fun buildCacheKey(dataSpec: DataSpec): String {
        dataSpec.key?.let { return it }
        return dataSpec.uri.toString()
    }
}
