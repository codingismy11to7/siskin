package com.cappielloantonio.tempo.util

import com.cappielloantonio.tempo.plex.api.library.LibraryClient
import java.net.URLDecoder

/**
 * The library query a smart playlist is defined by.
 *
 * Plex will not sort a playlist's items -- `sort=random` on
 * `playlists/{id}/items` is accepted and ignored, measured against PMS 1.43.3 --
 * so a smart playlist too large to fetch whole is sampled by re-issuing the
 * query it carries, with the sort replaced. See
 * docs/decisions/2026-08-28-mix-paging-design.md.
 *
 * **Decoded exactly once.** The value arrives doubly encoded and its inner
 * layer is load-bearing: `%253E%253E` is the `>>` comparison operator and must
 * reach the server as `%3E%3E`. Same hazard
 * [com.cappielloantonio.tempo.plex.api.library.LibraryService.getFirstCharacterContent]
 * documents, where re-encoding answered 200 with an empty list and no error
 * anywhere.
 */
object SmartPlaylistQuery {
    private const val PREFIX = "library://x/directory/"

    /** The relative path to follow, guarded by [LibraryClient.isSafeHubKey], or null. */
    @JvmStatic
    fun pathIn(content: String?): String? {
        if (content.isNullOrBlank()) return null
        if (!content.startsWith(PREFIX)) return null

        val decoded =
            runCatching { URLDecoder.decode(content.removePrefix(PREFIX), "UTF-8") }
                .getOrNull()
                ?: return null

        return decoded.takeIf { LibraryClient.isSafeHubKey(it) }
    }

    /**
     * [path] with its sort replaced with random rather than appended -- Plex
     * honours the first `sort` it sees, so appending to a query that already
     * carries one is a no-op that looks like a fix.
     */
    @JvmStatic
    fun randomised(path: String): String {
        val query = path.substringAfter('?', "")
        val base = path.substringBefore('?')
        val kept =
            query
                .split('&')
                .filter { it.isNotEmpty() && !it.startsWith("sort=") }

        return "$base?" + (kept + "sort=random").joinToString("&")
    }
}
