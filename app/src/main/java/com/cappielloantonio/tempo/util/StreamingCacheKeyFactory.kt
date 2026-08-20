package com.cappielloantonio.tempo.util

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheKeyFactory
import com.cappielloantonio.tempo.plex.PlexApi

/**
 * Cache key for the streaming cache. dataSpec.key, when a caller sets it,
 * identifies the audio bytes directly; nothing currently does, so this falls
 * through to the server's identity plus the part path.
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
 * The server is identified by its machineIdentifier rather than by the address
 * it currently answers on. Two servers can hand out the same part path for
 * different bytes, which is what the origin used to guard -- but an address is
 * a bad name for a server now that ServerAddressBook re-probes it, and keying
 * on one meant every recovery silently orphaned the whole cache.
 *
 * A session written before machineIdentifier existed falls back to the origin,
 * which keeps those working rather than collapsing every legacy session onto
 * one key.
 *
 * The Subsonic predecessor stripped its own tokenised parameters for this
 * reason; it was removed with the rest of the Subsonic-only preferences it
 * read, which reintroduced the defect the stripping existed to prevent.
 */
@UnstableApi
class StreamingCacheKeyFactory(
    private val api: PlexApi = PlexApi(),
) : CacheKeyFactory {
    override fun buildCacheKey(dataSpec: DataSpec): String {
        dataSpec.key?.let { return it }

        val machineIdentifier = api.machineIdentifier
        val path = dataSpec.uri.path
        // Restricted to part paths for the same reason ServerAddressResolver is:
        // those are the URIs whose origin is interchangeable. Dropping the origin
        // from anything else would collide two genuinely different resources that
        // happen to share a path.
        if (!machineIdentifier.isNullOrBlank() && path != null && path.startsWith(PART_PATH_PREFIX)) {
            return "$machineIdentifier$path"
        }

        return dataSpec.uri
            .buildUpon()
            .clearQuery()
            .build()
            .toString()
    }

    companion object {
        private const val PART_PATH_PREFIX = "/library/parts/"
    }
}
