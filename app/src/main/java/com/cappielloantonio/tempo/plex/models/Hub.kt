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

    /**
     * The server's own query for this row, e.g.
     * `/library/sections/7/all?type=9&genre=138884`.
     *
     * Not a preview link -- it is the full query behind the hub, and the only
     * way to open one: the parameters are rolled server-side, so a typed client
     * cannot rebuild them. `music.popular`'s key is a different endpoint
     * entirely (`/hubs/sections/7/popular?monthsAgo=4`), which is why following
     * the key is the only uniform way in.
     */
    var key: String? = null

    @SerializedName("Metadata")
    var metadata: List<Metadata>? = null
}
