package com.cappielloantonio.tempo.plex.models

import androidx.annotation.Keep

/**
 * A library section. Note Plex reports a music section's [type] as "artist",
 * not "music" -- that string is how the app finds music libraries.
 *
 * Also models a first-character bucket from the bucket index (see
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
