package com.cappielloantonio.tempo.plex.api.server

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Puts the live server address on a stream URL immediately before it is loaded.
 *
 * A track's URL is built once, when its MediaItem is made, and the queue outlives
 * the network the car was on when that happened. Without this, the player's
 * five-second re-prepare retries a URL whose host stopped existing -- which is
 * what "the music died and never came back" actually was.
 *
 * Only an http(s) URL whose path starts with `/library/parts/` is rewritten --
 * that is the Plex part-key signature, so `file://` locals and `content://`
 * artwork do not match regardless of what their path happens to look like,
 * and the rule needs no lookup against the candidate list: it is correct
 * whether the URL was built against a live address or a dead one.
 *
 * [ServerAddressBook.current] is a bare `scheme://host:port` today, so
 * rebuilding onto it is exact. A reverse-proxied server address --
 * `https://host/plex`, say -- would have that path silently dropped here, and
 * more immediately would never reach this rewrite at all: `book.current()`
 * comes only from plex.tv connection URIs, which are themselves bare
 * origins, so no incoming part URL is proxied either. Worth a sentence in
 * case anything ever lets a `serverUri` carry a path.
 */
@UnstableApi
class ServerAddressResolver(
    private val book: ServerAddressBook
) : ResolvingDataSource.Resolver {

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val scheme = dataSpec.uri.scheme
        if (scheme != "http" && scheme != "https") return dataSpec

        val path = dataSpec.uri.path ?: return dataSpec
        if (!path.startsWith(PART_PATH_PREFIX)) return dataSpec

        val base = book.current()?.toHttpUrlOrNull() ?: return dataSpec

        val rewritten: Uri = dataSpec.uri.buildUpon()
            .scheme(base.scheme)
            .encodedAuthority("${base.host}:${base.port}")
            .build()

        // Compared as rebuilt URI strings rather than short-circuited on a
        // host/port equality check. encodedAuthority above always writes an
        // explicit port, so the rebuilt string can never equal a port-less
        // original either way -- a host/port check would not save that
        // rewrite, and neither approach does. Where they actually differ is
        // scheme: a same-host, same-port URL on the wrong scheme
        // (http://live.example:32400/... vs the https base) fails a
        // host/port-only check and is left alone, while comparing the full
        // rebuilt string correctly rewrites it. Unreachable today -- the app
        // permits no cleartext traffic, so book.current() is always
        // https:// -- but it is why this compares the whole string.
        if (rewritten.toString() == dataSpec.uri.toString()) return dataSpec

        return dataSpec.withUri(rewritten)
    }

    companion object {
        private const val PART_PATH_PREFIX = "/library/parts/"
    }
}
