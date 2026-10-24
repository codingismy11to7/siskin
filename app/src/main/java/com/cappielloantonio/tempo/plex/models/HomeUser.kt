package com.cappielloantonio.tempo.plex.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

/** One member of a Plex Home, from GET /api/v2/home/users. */
@Keep
class HomeUser {
    var uuid: String? = null
    var title: String? = null
    var admin: Boolean? = null

    /** A managed user: no credentials of their own. */
    var restricted: Boolean? = null

    /** Has a PIN. `protected` is a Kotlin keyword, hence the rename. */
    @SerializedName("protected")
    var isProtected: Boolean? = null
}

@Keep
class HomeUsers {
    var users: List<HomeUser>? = null
}

/**
 * A plex.tv account, from GET /api/v2/user or a Home switch. After a switch,
 * [authToken] is the new member's plex.tv token -- which their own servers'
 * owner refuses; see the 2026-10-24 Home user switching design.
 */
@Keep
class PlexAccount {
    var authToken: String? = null
    var uuid: String? = null
    var title: String? = null
}

/** plex.tv's error envelope: `{"errors":[{"code":1041,...}]}`. */
@Keep
class PlexErrorBody {
    var errors: List<PlexError>? = null
}

@Keep
class PlexError {
    var code: Int? = null
}
