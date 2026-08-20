package com.cappielloantonio.tempo.plex

import com.cappielloantonio.tempo.plex.models.Directory

/**
 * A decade Directory as [PlexMediaMapper.decadeToMediaItem] receives it: only
 * [Directory.key] and [Directory.title] populated, matching what the decade
 * index actually returns -- see [Directory]'s KDoc. Shared between
 * PlexMediaMapperTest and PlexMediaMapperAssemblyTest, which both build one to
 * exercise the mapper from a different angle.
 */
fun decade(
    key: String? = "1980",
    title: String? = "1980s",
) = Directory().apply {
    this.key = key
    this.title = title
}
