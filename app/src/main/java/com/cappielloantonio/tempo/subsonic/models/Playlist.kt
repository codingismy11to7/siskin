package com.cappielloantonio.tempo.subsonic.models

import android.os.Parcelable
import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize
import java.util.Date

@Keep
@Parcelize
open class Playlist(
    open var id: String,
    var name: String? = null,
    var duration: Long = 0,
    @SerializedName("coverArt")
    var coverArtId: String? = null,
) : Parcelable {
    var comment: String? = null
    var owner: String? = null
    @SerializedName("public")
    var isUniversal: Boolean? = null
    var songCount: Int = 0
    var created: Date? = null
    var changed: Date? = null
    var allowedUsers: List<String>? = null
    constructor(
        id: String,
        name: String?,
        comment: String?,
        owner: String?,
        isUniversal: Boolean?,
        songCount: Int,
        duration: Long,
        created: Date?,
        changed: Date?,
        coverArtId: String?,
        allowedUsers: List<String>?,
    ) : this(id, name, duration, coverArtId) {
        this.comment = comment
        this.owner = owner
        this.isUniversal = isUniversal
        this.songCount = songCount
        this.created = created
        this.changed = changed
        this.allowedUsers = allowedUsers
    }
}