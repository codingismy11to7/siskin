package com.cappielloantonio.tempo.plex

import com.cappielloantonio.tempo.car.VehicleIdentity

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

    /** What a car that will not name itself falls back to. */
    private const val DEVICE = "Automotive"
    private const val MODEL = "automotive"

    @JvmStatic
    fun headers(
        clientIdentifier: String,
        appVersion: String,
        token: String?,
        language: String,
        vehicle: VehicleIdentity,
    ): Map<String, String> {
        val headers =
            linkedMapOf(
                "X-Plex-Client-Identifier" to clientIdentifier,
                "X-Plex-Product" to PRODUCT,
                "X-Plex-Version" to appVersion,
                "X-Plex-Platform" to PLATFORM,
                "Accept" to "application/json",
            )

        headers.putAll(deviceHeaders(vehicle))

        // Plex builds hub titles server-side and translates them -- "Keine
        // Wiedergabe seit 4 Monaten" -- so this is what keeps the Discover rows
        // in the car's language without a string resource per hub. Measured
        // against PMS 1.43.3: all five of Siskin's locales are covered, with
        // correct plurals. It also re-rolls each locale's hubs independently,
        // so the *content* differs between languages and not only the words;
        // see the 2026-08-14 hubs browse design.
        if (language.isNotBlank()) headers["X-Plex-Language"] = language

        // An empty X-Plex-Token reads to Plex as a failed auth, not an anonymous
        // call, so omit the header entirely when signed out.
        if (!token.isNullOrBlank()) headers["X-Plex-Token"] = token

        return headers
    }

    /**
     * The three headers that describe the car, split out because the Debug
     * screen reports exactly these and must not restate the mapping.
     *
     * All three are sent rather than only the one plex.tv's device list
     * renders, which is undocumented and unverified -- whichever it picks, the
     * row reads as the car. See the 2026-08-27 design.
     */
    @JvmStatic
    fun deviceHeaders(vehicle: VehicleIdentity): Map<String, String> =
        buildMap {
            put("X-Plex-Device", vehicle.make ?: DEVICE)
            put("X-Plex-Model", vehicle.model ?: MODEL)
            deviceName(vehicle)?.let { put("X-Plex-Device-Name", it) }
        }

    /** Null when there is no car to name, so the header is omitted rather than filled in. */
    private fun deviceName(vehicle: VehicleIdentity): String? {
        val make = vehicle.make ?: return null
        val model = vehicle.model ?: return null
        return listOfNotNull(vehicle.year?.toString(), make, model).joinToString(" ")
    }
}
