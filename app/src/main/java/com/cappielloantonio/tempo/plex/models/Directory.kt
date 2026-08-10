package com.cappielloantonio.tempo.plex.models

import androidx.annotation.Keep

/**
 * A library section. Note Plex reports a music section's [type] as "artist",
 * not "music" -- that string is how the app finds music libraries.
 *
 * Also models a decade entry from the decade index (see `LibraryClient.getDecades`),
 * which carries only [key] and [title] and no [type] at all -- filtering a decade
 * listing on [type] would drop every row.
 *
 * And a first-character bucket from the bucket index (see
 * `LibraryClient.getFirstCharacters`), which carries [key], [title] and [size]
 * and likewise no [type]. [size] is the bucket's item count and is populated
 * only there; the counts across one index sum to the section's totalSize, which
 * is what lets the browse tree decide a tab's shape without a second request.
 */
@Keep
class Directory {
    var key: String? = null
    var type: String? = null
    var title: String? = null
    var uuid: String? = null
    var agent: String? = null
    var size: Int? = null
}
