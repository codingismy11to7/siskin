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
 * reach the server as `%3E%3E`. This is the hazard
 * [com.cappielloantonio.tempo.plex.api.library.LibraryService.getFirstCharacterContent]
 * documents from the other direction, where re-encoding turned `%23` into
 * `%2523` and addressed a bucket that does not exist -- answered 200, with an
 * empty list and no error anywhere.
 */
object SmartPlaylistQuery {
    private const val PREFIX = "library://x/directory/"

    /**
     * The relative path to follow, or null when there is none safe to follow.
     *
     * Null rather than a failure for both refusals: a playlist whose content
     * cannot be re-issued is not an error, it is one the caller must sample
     * some other way.
     *
     * The host guard is [LibraryClient.isSafeHubKey]'s, unchanged and shared
     * rather than restated -- the reason is identical, which is that this
     * client attaches the account token to every request without inspecting
     * the target.
     */
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
     * [path] with its sort forced to random, whatever it said before.
     *
     * Replaced rather than appended: Plex honours the first `sort` it sees, so
     * appending to a query that already carries one is a no-op that looks like
     * a fix.
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
