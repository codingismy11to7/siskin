package com.cappielloantonio.tempo.plex.models

import androidx.annotation.Keep

/**
 * A device on the account, from GET /resources. Bare JSON -- the endpoint
 * returns a top-level array, not a MediaContainer.
 *
 * Media servers are those whose [provides] contains "server".
 */
@Keep
class Resource {
    var name: String? = null
    var clientIdentifier: String? = null

    /** Comma-separated capability list, e.g. "server,player". */
    var provides: String? = null
    var accessToken: String? = null
    var owned: Boolean? = null
    var connections: List<Connection>? = null
}

/**
 * A server this account does not own and holds no token for. The account's
 * plex.tv token is refused there, so there is nothing to sign in with.
 */
val Resource.refusesAccountToken: Boolean
    get() = owned == false && accessToken.isNullOrBlank()
