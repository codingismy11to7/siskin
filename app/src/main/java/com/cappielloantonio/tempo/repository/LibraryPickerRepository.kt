package com.cappielloantonio.tempo.repository

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionError
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.plex.LibrarySelection
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexIdentity
import com.cappielloantonio.tempo.plex.api.auth.AuthClient
import com.cappielloantonio.tempo.plex.api.auth.ServerProbe
import com.cappielloantonio.tempo.plex.api.library.LibraryClient
import com.cappielloantonio.tempo.plex.models.Resource
import com.cappielloantonio.tempo.util.ConstantsAA
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "LibraryPickerRepository"

/**
 * Serves the More > Select Library subtree: which servers the account has, which
 * music libraries each has, and committing the choice.
 *
 * Separate from PlexBrowseRepository because it talks to a different half of the
 * API -- plex.tv for discovery, and a *candidate* server that is deliberately
 * not the one the session points at -- and because that repository is already
 * large.
 */
@OptIn(UnstableApi::class)
class LibraryPickerRepository {

    private val api = PlexApi()
    private val authClient = AuthClient(api)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The server the user is currently looking inside, with the address that
     * answered. Held in memory and never persisted: a browse tree is something
     * users wander into and back out of, and if entering a server mutated the
     * stored session then backing out would leave a working install signed out.
     */
    @Volatile
    private var candidate: Pair<String, Resource>? = null

    fun getServers(): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
        scope.launch {
            try {
                authClient.getResources().fold(
                    { failure ->
                        // Narrowed to this node on purpose: the car may be on a LAN
                        // with a reachable Plex server and no route to plex.tv, in
                        // which case browsing music still works and only this screen
                        // is unavailable. Reporting it as "no servers" would read as
                        // an empty account.
                        Log.d(TAG, "could not list servers: $failure")
                        future.set(
                            LibraryResult.ofError(
                                SessionError(
                                    SessionError.ERROR_IO,
                                    App.getInstance()
                                        .getString(R.string.aa_library_picker_offline)
                                )
                            )
                        )
                    },
                    { resources ->
                        val items = serverRows(resources).map { row ->
                            browsableRow(
                                mediaId = ConstantsAA.PICK_SERVER_ID + row.machineIdentifier,
                                title = row.name
                            )
                        }
                        future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), null))
                    }
                )
            } catch (t: Throwable) {
                // Outside any either { } block, so this swallows no raise. media3
                // waits on this future; one that never completes leaves the tab
                // spinning until the car gives up.
                Log.w(TAG, "listing servers failed", t)
                future.setException(t)
            }
        }
        return future
    }

    fun getLibraries(machineIdentifier: String): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
        scope.launch {
            try {
                val resources = authClient.getResources().getOrNull()
                val resource = AuthClient.mediaServers(resources)
                    .firstOrNull { it.clientIdentifier == machineIdentifier }
                if (resource == null) {
                    Log.d(TAG, "server $machineIdentifier is no longer on the account")
                    future.set(LibraryResult.ofError(SessionError.ERROR_IO))
                    return@launch
                }

                val probe = ServerProbe(
                    headers = PlexIdentity.headers(api.clientIdentifier, api.appVersion, null)
                )
                val uri = probe.bestConnectionUri(resource)
                if (uri == null) {
                    Log.d(TAG, "no advertised connection answered for ${resource.name}")
                    future.set(LibraryResult.ofError(SessionError.ERROR_IO))
                    return@launch
                }

                candidate = uri to resource

                val sections = LibraryClient(api, uri, resource.accessToken)
                    .getSections()
                    .getOrNull()
                val session = api.session
                val items = LibraryClient.musicSections(sections).map { directory ->
                    val key = directory.key.orEmpty()
                    browsableRow(
                        mediaId = ConstantsAA.PICK_LIBRARY_ID +
                            libraryIdPayload(machineIdentifier, key),
                        title = libraryRowTitle(
                            directory.title.orEmpty().ifBlank { "Library $key" },
                            LibrarySelection.isCurrent(session, machineIdentifier, uri, key)
                        )
                    )
                }
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), null))
            } catch (t: Throwable) {
                Log.w(TAG, "listing libraries failed", t)
                future.setException(t)
            }
        }
        return future
    }

    companion object {

        data class ServerRow(val machineIdentifier: String?, val name: String)

        /** U+2713 CHECK MARK. The tick is the only signal of which library is in use. */
        private const val TICK = "✓ "

        @JvmStatic
        fun libraryRowTitle(name: String, current: Boolean): String =
            if (current) TICK + name else name

        @JvmStatic
        fun libraryIdPayload(machineIdentifier: String, sectionKey: String): String =
            "$machineIdentifier|$sectionKey"

        /**
         * Narrows /resources to servers this app could talk to, reusing the same
         * rule sign-in uses so the picker and the sign-in screen never disagree
         * about which servers exist.
         */
        @JvmStatic
        fun serverRows(resources: List<Resource>?): List<ServerRow> =
            AuthClient.mediaServers(resources).map { resource ->
                ServerRow(
                    machineIdentifier = resource.clientIdentifier,
                    name = resource.name.orEmpty().ifBlank { "Plex server" }
                )
            }

        @JvmStatic
        fun browsableRow(mediaId: String, title: String): MediaItem =
            MediaItem.Builder()
                .setMediaId(mediaId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setIsBrowsable(true)
                        // Never playable: a playable row opens Now Playing on tap
                        // and nothing the app returns can suppress that.
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build()
                )
                .build()
    }
}
