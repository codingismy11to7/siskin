package com.cappielloantonio.tempo.plex.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

/**
 * One row from GET /hubs/sections/{id}, e.g. "Recently Added" or "On Deck".
 * [hubIdentifier] names which row this is, [more] indicates the row has more
 * items than are included here, and [size] is the item count in this response.
 *
 * Per the Plex OpenAPI spec's Hub schema, hub items are always [metadata] --
 * unlike a MediaContainer, a Hub never carries a Directory array.
 */
@Keep
class Hub {
    var hubIdentifier: String? = null
    var title: String? = null
    var type: String? = null
    var size: Int? = null
    var more: Boolean? = null

    @SerializedName("Metadata")
    var metadata: List<Metadata>? = null
}
