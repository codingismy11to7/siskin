package com.cappielloantonio.tempo.plex

import android.util.Log
import com.cappielloantonio.tempo.plex.base.PlexResponse
import com.cappielloantonio.tempo.plex.models.Metadata

private const val TAG = "FolderRows"

/**
 * One level of `/library/sections/{id}/folder`. Folders and tracks both arrive
 * under `Metadata`; each row is classified by its key.
 *
 * A folder row's key is a path the server chose; only the digits after
 * `parent=` are kept, and only from this section's own folder endpoint, so
 * nothing a response says can make Siskin send its token anywhere else. See
 * docs/decisions/2026-11-06-folder-browse-design.md.
 */
object FolderRows {
    sealed interface Row {
        data class Folder(
            val token: FolderToken,
            val title: String,
        ) : Row

        data class Track(
            val metadata: Metadata,
        ) : Row
    }

    private const val TRACK_KEY_PREFIX = "/library/metadata/"
    private const val TYPE_TRACK = "track"

    fun classify(
        response: PlexResponse?,
        section: SectionKey,
    ): List<Row> {
        val folderKeyPrefix = "/library/sections/${section.value}/folder?parent="
        val metadata = response?.mediaContainer?.metadata.orEmpty()

        val rows =
            metadata.mapNotNull { row ->
                if (row.key?.startsWith(TRACK_KEY_PREFIX) == true) {
                    row.takeIf { it.type == TYPE_TRACK && !it.ratingKey.isNullOrBlank() }?.let(Row::Track)
                } else {
                    folder(row.key, row.title, folderKeyPrefix)
                }
            }

        val dropped = metadata.size - rows.size
        if (dropped > 0) Log.w(TAG, "dropped $dropped folder rows that were neither a track nor this section's folder")
        return rows
    }

    private fun folder(
        key: String?,
        title: String?,
        prefix: String,
    ): Row.Folder? {
        val token = key?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
        if (token.isNullOrEmpty() || !token.all { it in '0'..'9' }) return null
        val name = title?.takeIf { it.isNotBlank() } ?: return null
        return Row.Folder(FolderToken(token), name)
    }
}
