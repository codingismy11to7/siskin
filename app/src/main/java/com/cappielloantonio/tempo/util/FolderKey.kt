package com.cappielloantonio.tempo.util

import com.cappielloantonio.tempo.plex.FolderToken

/**
 * The payload of a [Constants.FOLDER_ID] row: which library, then which folder
 * (empty for the section's root).
 *
 * The scope is here for [HubKey]'s reason: a folder token is a number that
 * means something only on one server, so a row the car cached before a library
 * switch would otherwise open an unrelated folder on the new one.
 * `PlexBrowseRepository` compares it with
 * [com.cappielloantonio.tempo.provider.CompositeArt.currentScope] before any
 * request.
 *
 * A [Constants.FOLDER_WINDOW_ID] row appends `|<start>` to the same payload.
 * Neither a scope nor a token can contain a pipe, which is what makes the
 * splits below unambiguous.
 */
object FolderKey {
    private const val SEPARATOR = '|'

    data class Folder(
        val scope: String,
        val token: FolderToken?,
    )

    fun of(
        scope: String,
        token: FolderToken?,
    ): String = "$scope$SEPARATOR${token?.value.orEmpty()}"

    fun parse(payload: String): Folder? {
        val parts = payload.split(SEPARATOR)
        if (parts.size != 2 || parts[0].isEmpty()) return null
        val token = parts[1]
        return when {
            token.isEmpty() -> Folder(parts[0], null)
            token.all { it in '0'..'9' } -> Folder(parts[0], FolderToken(token))
            else -> null
        }
    }

    /** What a window id's start offset is appended to. */
    fun windowPrefix(payload: String): String = "$payload$SEPARATOR"

    /** A window id's remainder split into its folder payload and start offset. */
    fun parseWindow(remainder: String): Pair<String, Int>? {
        val payload = remainder.substringBeforeLast(SEPARATOR, missingDelimiterValue = "")
        val start = remainder.substringAfterLast(SEPARATOR).toIntOrNull() ?: return null
        if (start < 0 || parse(payload) == null) return null
        return payload to start
    }
}
