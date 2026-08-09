package com.cappielloantonio.tempo.plex.models

import androidx.annotation.Keep

/**
 * A library section. Note Plex reports a music section's [type] as "artist",
 * not "music" -- that string is how the app finds music libraries.
 *
 * Also models a decade entry from the decade index (see `LibraryClient.getDecades`),
 * which carries only [key] and [title] and no [type] at all -- filtering a decade
 * listing on [type] would drop every row.
 */
@Keep
class Directory {
    var key: String? = null
    var type: String? = null
    var title: String? = null
    var uuid: String? = null
    var agent: String? = null
}
