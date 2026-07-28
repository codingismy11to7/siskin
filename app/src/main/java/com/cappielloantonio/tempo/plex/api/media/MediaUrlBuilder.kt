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

    fun artworkUrl(
        serverUri: String?,
        thumbPath: String?,
        token: String?,
        width: Int,
        height: Int
    ): String? {
        val base = normalizeBase(serverUri) ?: return null
        if (thumbPath.isNullOrBlank() || token.isNullOrBlank()) return null

        return "$base/photo/:/transcode" +
            "?width=$width&height=$height&minSize=1" +
            "&url=${encode(thumbPath)}" +
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
