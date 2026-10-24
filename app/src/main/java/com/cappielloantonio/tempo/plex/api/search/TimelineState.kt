package com.cappielloantonio.tempo.plex.api.search

/**
 * The transport state Plex's `/:/timeline` accepts.
 *
 * [wire] is what goes on the request. Retrofit would send the constant's name,
 * `PLAYING`, so the service takes a `String` and SearchClient passes [wire].
 */
enum class TimelineState(
    val wire: String,
) {
    PLAYING("playing"),
    PAUSED("paused"),
    STOPPED("stopped"),
}
