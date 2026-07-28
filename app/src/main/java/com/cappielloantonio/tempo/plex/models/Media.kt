package com.cappielloantonio.tempo.plex.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

/** One encoding of a track. A track can have several. */
@Keep
class Media {
    var id: Long? = null
    var duration: Long? = null
    var bitrate: Int? = null
    var audioCodec: String? = null
    var container: String? = null

    @SerializedName("Part")
    var part: List<Part>? = null
}
