package com.cappielloantonio.tempo.plex.models

import androidx.annotation.Keep

/**
 * One reachable address for a [Resource]. A server usually advertises several --
 * a LAN address, a plex.direct address and possibly a relay. [uri] is the one to
 * use; [local] and [relay] rank them.
 */
@Keep
class Connection {
    var protocol: String? = null
    var address: String? = null
    var port: Int? = null
    var uri: String? = null
    var local: Boolean? = null
    var relay: Boolean? = null
}
