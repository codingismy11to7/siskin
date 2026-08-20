package com.cappielloantonio.tempo.plex

/**
 * Which music library section. Plex calls it a section key.
 *
 * A distinct type from [RatingKey] because both are strings, both index into
 * Plex, and the calls that take them sit next to each other --
 * `getSectionContent(sectionKey)` beside `getChildren(ratingKey)`. Swapping them
 * used to compile and fail at runtime against a live server.
 */
@JvmInline
value class SectionKey(
    val value: String,
)

/** Which item -- artist, album, track or playlist. Plex calls it a rating key. */
@JvmInline
value class RatingKey(
    val value: String,
)
