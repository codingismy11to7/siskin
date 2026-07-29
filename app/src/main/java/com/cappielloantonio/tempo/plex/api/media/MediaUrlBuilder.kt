package com.cappielloantonio.tempo.plex.api.media

import java.net.URLEncoder

/**
 * Builds the two Plex URLs that are handed to something else rather than fetched
 * by Retrofit: artwork (consumed by the artwork ContentProvider) and audio
 * streams (consumed by ExoPlayer).
 *
 * Deliberately uses java.net.URLEncoder rather than android.net.Uri so these stay
 * pure functions -- app/build.gradle sets unitTests.returnDefaultValues = true,
 * under which Uri would silently return null in tests.
 */
object MediaUrlBuilder {

    /**
     * True only for a path Plex will resolve against its own library.
     *
     * Load-bearing because of what `url=` means to Plex's photo transcoder: it
     * fetches whatever that parameter names, an absolute URL on another host
     * included. A thumb path that is not server-relative therefore turns the
     * user's own Plex server into a proxy that will fetch an arbitrary URL with
     * the user's credentials and hand the bytes back to whoever asked -- and
     * AlbumArtContentProvider, which is exported, is a way for any app on the
     * head unit to ask.
     *
     * A genuine Plex thumb is always server-relative:
     * `/library/metadata/1234/thumb/1699999999`,
     * `/playlists/169077/composite/1781213364`. `//host/path` is rejected as
     * well -- it begins with a slash but is protocol-relative, so a URL parser
     * reads it as another host -- and so is any path containing a backslash,
     * which several parsers normalise to a forward slash.
     */
    @JvmStatic
    fun isServerRelativePath(path: String?): Boolean =
        !path.isNullOrBlank() &&
            path.startsWith("/") &&
            !path.startsWith("//") &&
            !path.contains('\\')

    fun artworkUrl(
        serverUri: String?,
        thumbPath: String?,
        token: String?,
        width: Int,
        height: Int
    ): String? {
        val base = normalizeBase(serverUri) ?: return null
        // Defence in depth behind AlbumArtContentProvider's own check: this is
        // the function that composes the transcode URL, so refusing here means
        // no future caller can reintroduce the proxy by forgetting to validate.
        val thumb = thumbPath?.takeIf { isServerRelativePath(it) } ?: return null
        if (token.isNullOrBlank()) return null

        return "$base/photo/:/transcode" +
            "?width=$width&height=$height&minSize=1" +
            "&url=${encode(thumb)}" +
            "&X-Plex-Token=${encode(token)}"
    }

    fun streamUrl(serverUri: String?, partKey: String?, token: String?): String? {
        val base = normalizeBase(serverUri) ?: return null
        if (partKey.isNullOrBlank() || token.isNullOrBlank()) return null

        return "$base$partKey?X-Plex-Token=${encode(token)}"
    }

    /** Discovered connection URIs sometimes carry a trailing slash; doubling it 404s. */
    private fun normalizeBase(serverUri: String?): String? {
        if (serverUri.isNullOrBlank()) return null
        return serverUri.trim().trimEnd('/')
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
