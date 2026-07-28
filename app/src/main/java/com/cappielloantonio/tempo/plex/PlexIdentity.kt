package com.cappielloantonio.tempo.plex

/**
 * The X-Plex-* headers Plex requires on every request, including the very first
 * one that creates a PIN.
 *
 * Pure and parameterised rather than reading BuildConfig or Preferences directly,
 * so it can be unit-tested -- app/build.gradle sets
 * unitTests.returnDefaultValues = true, which would stub anything framework-bound.
 */
object PlexIdentity {

    private const val PRODUCT = "Siskin"
    private const val PLATFORM = "Android"

    /** Shown in the Plex account's device list; distinguishes a head unit from a phone. */
    private const val DEVICE = "Automotive"
    private const val MODEL = "automotive"

    @JvmStatic
    fun headers(
        clientIdentifier: String,
        appVersion: String,
        token: String?
    ): Map<String, String> {
        val headers = linkedMapOf(
            "X-Plex-Client-Identifier" to clientIdentifier,
            "X-Plex-Product" to PRODUCT,
            "X-Plex-Version" to appVersion,
            "X-Plex-Platform" to PLATFORM,
            "X-Plex-Device" to DEVICE,
            "X-Plex-Model" to MODEL,
            "Accept" to "application/json"
        )

        // An empty X-Plex-Token reads to Plex as a failed auth, not an anonymous
        // call, so omit the header entirely when signed out.
        if (!token.isNullOrBlank()) headers["X-Plex-Token"] = token

        return headers
    }
}
