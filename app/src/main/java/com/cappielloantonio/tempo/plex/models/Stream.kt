package com.cappielloantonio.tempo.plex.models

import androidx.annotation.Keep

/**
 * One stream of a [Part]. The ramps are present only when the request asks for
 * `includeLoudnessRamps=1`.
 */
@Keep
class Stream {
    var streamType: Int? = null
    var startRamp: String? = null
    var endRamp: String? = null
}
