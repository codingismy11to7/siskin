package com.cappielloantonio.tempo.plex

/**
 * Plex's numeric metadata types, as used by the `type` parameter on library
 * listing and search endpoints.
 *
 * Shared rather than owned by one client because both `LibraryClient` (filtering a
 * section listing) and `SearchClient` (which cannot search without one) need the
 * same three values.
 *
 * Only one value is accepted per request -- verified against PMS 1.43.3, where a
 * comma-separated `type=8,9,10` is rejected with HTTP 400. Searching artists,
 * albums and tracks together therefore means three calls, which is the browse
 * layer's decision to make rather than this layer's.
 */
object PlexItemType {
    const val ARTIST = 8
    const val ALBUM = 9
    const val TRACK = 10
}
