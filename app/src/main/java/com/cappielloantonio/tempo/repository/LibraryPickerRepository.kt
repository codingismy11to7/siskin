package com.cappielloantonio.tempo.repository

import android.util.Log
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
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
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.SectionKey
import com.cappielloantonio.tempo.plex.api.auth.AuthClient
import com.cappielloantonio.tempo.plex.api.auth.ServerProbe
import com.cappielloantonio.tempo.plex.api.library.LibraryClient
import com.cappielloantonio.tempo.plex.models.Resource
import com.cappielloantonio.tempo.service.BrowseTreeInvalidator
import com.cappielloantonio.tempo.service.CarSignInResolution
import com.cappielloantonio.tempo.util.ConstantsAA
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * Plain, un-ticked display name for each library row, keyed by its
     * [libraryIdPayload]. [getLibraries] records one entry per row it builds;
     * [selectLibrary] reads it back to name the confirmation row.
     *
     * This is **not** a cache that could be dropped to save memory: the
     * picker's rows are built ad hoc in [getLibraries] and never registered
     * with `MediaBrowserTree` (only `buildTree()`'s fixed nodes are), so once
     * an entry is gone there is nowhere else `selectLibrary` could recover
     * that name from. Held in memory only, like [candidate] -- never
     * persisted, and empty again after a process restart, which
     * [selectLibrary] covers with a fallback title.
     *
     * [getLibraries] writes from a background IO coroutine and [selectLibrary]
     * reads from whatever thread the car's callback runs on, so this has to be
     * a thread-safe structure rather than a plain map.
     */
    private val libraryNames = ConcurrentHashMap<String, String>()

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
                    val payload = libraryIdPayload(machineIdentifier, key)
                    val plainName = directory.title.orEmpty().ifBlank { "Library $key" }
                    // Recorded before ticking so selectLibrary never has to
                    // strip the tick back off later.
                    libraryNames[payload] = plainName
                    browsableRow(
                        mediaId = ConstantsAA.PICK_LIBRARY_ID + payload,
                        title = libraryRowTitle(
                            plainName,
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

    /**
     * Commits the pick, then answers with a confirmation row.
     *
     * The confirmation is usually never drawn: invalidating the parent below
     * makes the car re-render the list it is already showing, which supersedes
     * the screen it was about to push. That is a race, not a guarantee -- a
     * slower head unit may land the push -- so the row is what the user sees when
     * the invalidation loses. Returning an empty list instead would degrade into
     * a blank screen. **Do not delete this row because it appears never to
     * render.**
     */
    fun selectLibrary(payload: String): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
        val machineIdentifier = payload.substringBefore('|')
        val sectionKey = payload.substringAfter('|', "")
        val held = candidate

        if (sectionKey.isBlank() || held == null || held.second.clientIdentifier != machineIdentifier) {
            // Reachable if the process was restarted between listing the
            // libraries and tapping one: the candidate lives in memory only.
            Log.d(TAG, "no candidate server for $machineIdentifier; re-enter the server")
            future.set(LibraryResult.ofError(SessionError.ERROR_INVALID_STATE))
            return future
        }

        val (uri, resource) = held
        val accountToken = api.accountToken
        if (accountToken.isNullOrBlank()) {
            // Routed through CarSignInResolution rather than raised bare: only the
            // error *it* builds reaches the car as a tappable "Sign in" button.
            // See its KDoc -- a plain ofError here is a dead end.
            future.set(
                CarSignInResolution.errorResult(
                    App.getInstance(), R.string.car_sign_in_again
                )
            )
            return future
        }

        val previous = api.session
        val next = PlexSession(
            accountToken = accountToken,
            serverUri = uri,
            musicSectionKey = SectionKey(sectionKey),
            serverToken = resource.accessToken,
            machineIdentifier = resource.clientIdentifier
        )

        if (LibrarySelection.invalidatesQueue(previous, next)) {
            // Rating keys are server-wide, so only a server change makes the
            // saved queue meaningless; its partKeys would otherwise be rebuilt
            // against a server they never came from.
            Log.d(TAG, "server changed; discarding the saved queue")
            QueueRepository().deleteAll()
        }

        api.session = next

        // The three music tabs are showing the old library. PlexBrowseRepository
        // rebuilds its own clients on the next call because refreshClients()
        // compares sessions, so only the car needs telling.
        BrowseTreeInvalidator.invalidateRoot()
        BrowseTreeInvalidator.invalidateNode(
            ConstantsAA.PICK_SERVER_ID + machineIdentifier,
            0
        )

        // Not a MediaBrowserTree.getItem lookup: the tree's static nodes are
        // only ever the ones buildTree() registers, so this row's id would
        // never resolve there. libraryNames is the only place this name
        // lives; the fallback covers a process restart between listing the
        // libraries and tapping one, when the map is empty again.
        val name = libraryNames[payload] ?: "Library $sectionKey"
        future.set(
            LibraryResult.ofItemList(
                ImmutableList.of(
                    browsableRow(
                        mediaId = ConstantsAA.PICK_LIBRARY_ID + payload + ":confirmed",
                        // Browsable purely so the car draws it: an item with
                        // neither isBrowsable nor isPlayable set is dropped from
                        // the list entirely.
                        title = App.getInstance()
                            .getString(R.string.aa_now_browsing, name)
                    )
                ),
                null
            )
        )
        return future
    }

    /**
     * Test seam only. A live [getLibraries] is the only production writer of
     * [candidate] and [libraryNames], and it needs a real plex.tv round trip
     * plus a server probe -- neither available under Robolectric. Tests use
     * this to put a fresh repository into the state [getLibraries] would have
     * left it in, so [selectLibrary] can be exercised without restructuring
     * its signature or widening either field's visibility beyond this seam.
     */
    @VisibleForTesting
    internal fun primeCandidateForTest(
        uri: String,
        resource: Resource,
        sectionKey: String,
        libraryName: String
    ) {
        candidate = uri to resource
        val machineIdentifier = requireNotNull(resource.clientIdentifier) {
            "test resource needs a clientIdentifier"
        }
        libraryNames[libraryIdPayload(machineIdentifier, sectionKey)] = libraryName
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
