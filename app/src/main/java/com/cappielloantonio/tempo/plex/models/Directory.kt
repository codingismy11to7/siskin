package com.cappielloantonio.tempo.plex.models

import androidx.annotation.Keep

/**
 * A library section. Note Plex reports a music section's [type] as "artist",
 * not "music" -- that string is how the app finds music libraries.
 */
@Keep
class Directory {
    var key: String? = null
    var type: String? = null
    var title: String? = null
    var uuid: String? = null
    var agent: String? = null
}
