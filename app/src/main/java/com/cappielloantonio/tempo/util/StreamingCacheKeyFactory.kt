package com.cappielloantonio.tempo.util

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheKeyFactory

/**
 * Cache key for the streaming cache. dataSpec.key, when a caller sets it,
 * identifies the audio bytes directly; nothing currently does, so this falls
 * through to the request URL with its query string dropped.
 *
 * Dropping the query is the whole point. `MediaUrlBuilder.streamUrl` builds
 * `<server><partKey>?X-Plex-Token=<token>`, so keying on the full URI would
 * write the user's Plex token into the on-disk cache index, and would orphan
 * every cached entry the moment that token rotated -- the same track would ask
 * for a key it had never stored under and re-download. Persisting the part
 * path rather than a tokenised URL is the same decision the Room entities
 * make, for the same reason (see
 * docs/decisions/2026-07-28-plex-browse-playback-design.md, "partKey rather
 * than a stream URL").
 *
 * Note the key that remains is `<server-origin><partKey>`, not `partKey`
 * alone, so a change of *server address* -- LAN to remote, or either to a
 * plex.direct relay -- still orphans the cache. Only the token churn is
 * solved here. Including the origin is deliberate: two servers can hand out
 * the same part path for different bytes.
 *
 * The Subsonic predecessor stripped its own tokenised parameters for this
 * reason; it was removed with the rest of the Subsonic-only preferences it
 * read, which reintroduced the defect the stripping existed to prevent.
 */
@UnstableApi
class StreamingCacheKeyFactory : CacheKeyFactory {
    override fun buildCacheKey(dataSpec: DataSpec): String {
        dataSpec.key?.let { return it }
        return dataSpec.uri.buildUpon().clearQuery().build().toString()
    }
}
