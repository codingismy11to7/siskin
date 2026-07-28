package com.cappielloantonio.tempo.plex.models

import androidx.annotation.Keep

/**
 * A plex.tv OAuth PIN. Bare JSON -- no MediaContainer envelope.
 *
 * [code] is shown to the user; [qr] is a ready-made QR image URL Plex generates,
 * so the sign-in screen does not have to render one itself. [authToken] stays
 * null until the user approves the pin elsewhere.
 */
@Keep
class Pin {
    var id: Long? = null
    var code: String? = null
    var clientIdentifier: String? = null
    /** Seconds from issue. */
    var expiresIn: Int? = null
    /** ISO-8601, e.g. "2026-07-27T12:00:00Z". */
    var expiresAt: String? = null
    var authToken: String? = null
    var trusted: Boolean? = null
    var qr: String? = null
}
