package com.cappielloantonio.tempo.plex.api.auth

/**
 * A PIN that is actually usable: it has the id the poll loop needs and the code
 * the screen shows.
 *
 * The wire model [com.cappielloantonio.tempo.plex.models.Pin] has every field
 * nullable, because Gson builds it from whatever plex.tv sent. Validating once,
 * here, is what lets the sign-in flow stop re-checking `id` at every step -- and
 * removes the "afterPinCreated only returns AwaitingApproval for a pin with an
 * id" comment that used to stand in for a type.
 */
data class CreatedPin(
    val id: Long,
    val code: String,
    /** Null when Plex omitted it or sent it blank; the screen shows the code alone. */
    val qrUrl: String?,
    /** Null when absent or unparseable; PlexPinState.shouldKeepPolling bounds the loop. */
    val expiresAtEpochSeconds: Long?
)
