package com.cappielloantonio.tempo.plex.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

/**
 * An item's stations, from `includeStations=1`. An object wrapping
 * [metadata], not an array -- unlike most of Plex's child containers.
 */
@Keep
class Stations {
    @SerializedName("Metadata")
    var metadata: List<Metadata>? = null
}
