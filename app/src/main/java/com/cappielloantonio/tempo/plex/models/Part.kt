package com.cappielloantonio.tempo.plex.models

import androidx.annotation.Keep

/**
 * One file backing a [Media]. [key] is the server-relative path that
 * MediaUrlBuilder.streamUrl turns into a playable URL.
 */
@Keep
class Part {
    var id: Long? = null
    var key: String? = null
    var duration: Long? = null
    var file: String? = null
    var size: Long? = null
    var container: String? = null
}
