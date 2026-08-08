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
 * Only `/library/parts/` is rewritten. That is the Plex part-key signature, so
 * local files and content:// artwork do not match, and the rule needs no lookup
 * against the candidate list -- it is correct whether the URL was built against
 * a live address or a dead one.
 */
@UnstableApi
class ServerAddressResolver(
    private val book: ServerAddressBook
) : ResolvingDataSource.Resolver {

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val path = dataSpec.uri.path ?: return dataSpec
        if (!path.startsWith(PART_PATH_PREFIX)) return dataSpec

        val base = book.current()?.toHttpUrlOrNull() ?: return dataSpec

        val rewritten: Uri = dataSpec.uri.buildUpon()
            .scheme(base.scheme)
            .encodedAuthority("${base.host}:${base.port}")
            .build()

        // Compared as rebuilt URI strings rather than short-circuited on
        // Uri.getPort()/HttpUrl.port: a URL with no explicit port reports -1
        // from the former and the scheme default (e.g. 443) from the latter,
        // so a host/port equality check would rewrite an already-correct URL
        // that happened to omit its port.
        if (rewritten.toString() == dataSpec.uri.toString()) return dataSpec

        return dataSpec.withUri(rewritten)
    }

    companion object {
        private const val PART_PATH_PREFIX = "/library/parts/"
    }
}
