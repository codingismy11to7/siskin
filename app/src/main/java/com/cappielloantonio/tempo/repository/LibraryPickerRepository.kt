package com.cappielloantonio.tempo.repository

import android.util.Log
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionError
import arrow.core.getOrElse
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.util.ResourceUris
import com.cappielloantonio.tempo.plex.LibrarySelection
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexIdentity
import com.cappielloantonio.tempo.plex.PlexSession
import com.cappielloantonio.tempo.plex.SectionKey
import com.cappielloantonio.tempo.plex.api.auth.AuthClient
import com.cappielloantonio.tempo.plex.api.library.LibraryClient
import com.cappielloantonio.tempo.plex.api.server.ServerAddressBook
import com.cappielloantonio.tempo.plex.api.server.ServerProbe
import com.cappielloantonio.tempo.plex.models.Resource
import com.cappielloantonio.tempo.service.BrowseTreeInvalidator
import com.cappielloantonio.tempo.service.CarSignInResolution
import com.cappielloantonio.tempo.util.Constants
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
    private val addressBook = ServerAddressBook.shared
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Every server the user has looked inside this session, with the address
     * that answered, keyed by machine identifier. Held in memory and never
     * persisted: a browse tree is something users wander into and back out of,
     * and if entering a server mutated the stored session then backing out would
     * leave a working install signed out.
     *
     * A map rather than a single slot because the car caches a browse list and
     * does not re-fetch it on a back-navigation: enter server A, enter server B,
     * back out to A's still-cached library list and tap a row, and a one-slot
     * candidate would hold B while the payload names A. That fails closed --
     * nothing is written -- but on a screen that looks perfectly fine.
     *
     * Same threading story as [libraryNames]: written from [getLibraries]'s IO
     * coroutine and read from whatever thread the car's callback runs on.
     */
    private val candidates = ConcurrentHashMap<String, Pair<String, Resource>>()

    /**
     * Plain, un-ticked display name for each library row, keyed by its
     * [libraryIdPayload]. [getLibraries] records one entry per row it builds;
     * [selectLibrary] reads it back to name the confirmation row.
     *
     * This is **not** a cache that could be dropped to save memory: the
     * picker's rows are built ad hoc in [getLibraries] and never registered
     * with `MediaBrowserTree` (only `buildTree()`'s fixed nodes are), so once
     * an entry is gone there is nowhere else `selectLibrary` could recover
     * that name from. Held in memory only, like [candidates] -- never
     * persisted, and empty again after a process restart, which
     * [confirmationRow] covers with a fallback title.
     *
     * [getLibraries] writes from a background IO coroutine and [selectLibrary]
     * reads from whatever thread the car's callback runs on, so this has to be
     * a thread-safe structure rather than a plain map.
     */
    private val libraryNames = ConcurrentHashMap<String, String>()

    /**
     * Wraps a message in the single-row result the picker uses to report
     * failure. See [messageRow] for why this is not a [LibraryResult] error.
     */
    private fun messageResult(
        @StringRes messageRes: Int
    ): LibraryResult<ImmutableList<MediaItem>> =
        LibraryResult.ofItemList(
            ImmutableList.of(messageRow(App.getContext().getString(messageRes))),
            null
        )

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
                        // an empty account, and the stored session is deliberately
                        // left alone -- the current library keeps working.
                        Log.d(TAG, "could not list servers: $failure")
                        future.set(messageResult(R.string.browse_library_picker_offline))
                    },
                    { resources ->
                        val session = api.session
                        val items = serverRows(resources).map { row ->
                            browsableRow(
                                mediaId = Constants.PICK_SERVER_ID + row.machineIdentifier,
                                title = rowTitle(
                                    row.name,
                                    LibrarySelection.isCurrentServer(session, row.machineIdentifier)
                                ),
                                iconRes = R.drawable.ic_browse_server
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
                // Three separate causes, three separate sentences: a plex.tv
                // outage, a server that has left the account, and a server that
                // will not answer are indistinguishable to the user if they all
                // land on the car's generic failure screen.
                val resources = authClient.getResources().getOrElse { failure ->
                    Log.d(TAG, "could not re-list servers: $failure")
                    future.set(messageResult(R.string.browse_library_picker_offline))
                    return@launch
                }
                val resource = AuthClient.mediaServers(resources)
                    .firstOrNull { it.clientIdentifier == machineIdentifier }
                if (resource == null) {
                    Log.d(TAG, "server $machineIdentifier is no longer on the account")
                    future.set(messageResult(R.string.browse_library_picker_server_gone))
                    return@launch
                }

                val probe = ServerProbe(
                    headers = PlexIdentity.headers(api.clientIdentifier, api.appVersion, null, api.language)
                )
                val uri = probe.bestConnectionUri(resource)
                if (uri == null) {
                    Log.d(TAG, "no advertised connection answered for ${resource.name}")
                    future.set(messageResult(R.string.browse_library_picker_server_unreachable))
                    return@launch
                }

                candidates[machineIdentifier] = uri to resource

                val sections = LibraryClient(api, uri, resource.accessToken)
                    .getSections()
                    .getOrElse { failure ->
                        // It answered ServerProbe a moment ago, so this is the
                        // server going away mid-navigation rather than a wrong
                        // address -- the same sentence either way.
                        Log.d(TAG, "could not list sections for ${resource.name}: $failure")
                        future.set(messageResult(R.string.browse_library_picker_server_unreachable))
                        return@launch
                    }
                val musicSections = LibraryClient.musicSections(sections)
                if (musicSections.isEmpty()) {
                    // A photo-and-video-only server is a real answer rather than
                    // a failure, but an empty list renders as a blank screen.
                    Log.d(TAG, "${resource.name} has no music sections")
                    future.set(messageResult(R.string.browse_library_picker_no_music))
                    return@launch
                }
                val session = api.session
                val items = musicSections.map { directory ->
                    val key = directory.key.orEmpty()
                    val payload = libraryIdPayload(machineIdentifier, key)
                    val plainName = directory.title.orEmpty().ifBlank { "Library $key" }
                    // Recorded before ticking so selectLibrary never has to
                    // strip the tick back off later.
                    libraryNames[payload] = plainName
                    browsableRow(
                        mediaId = Constants.PICK_LIBRARY_ID + payload,
                        title = rowTitle(
                            plainName,
                            LibrarySelection.isCurrent(session, machineIdentifier, uri, key)
                        ),
                        iconRes = R.drawable.ic_browse_library
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
        val held = candidates[machineIdentifier]

        // The clientIdentifier check is a backstop now that the map is keyed by
        // the same value: it only fires if getLibraries ever files a resource
        // under a key that is not its own.
        if (sectionKey.isBlank() || held == null || held.second.clientIdentifier != machineIdentifier) {
            // Reachable if the process was restarted between listing the
            // libraries and tapping one: the candidates live in memory only.
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
                    App.getContext(), R.string.car_sign_in_again
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
            // Room is only half of the queue. ExoPlayer's timeline still holds
            // the old server's URLs, so without this the current track plays on,
            // the next one 404s, and BaseMediaService.onPlayerError re-prepares
            // that dead URL every five seconds.
            BrowseTreeInvalidator.stopPlayback()
        }

        // Recorded before the session write below so a re-probe has a list to
        // race the moment this session exists, rather than only after the
        // first recovery escalates all the way to plex.tv.
        addressBook.adopt(resource, uri)

        api.session = next

        // The three music tabs are showing the old library. PlexBrowseRepository
        // rebuilds its own clients on the next call because refreshClients()
        // compares sessions, so only the car needs telling.
        //
        // These two have opposite threading contracts -- invalidateRoot() must be
        // called on the main thread, invalidateNode() posts to it -- which is only
        // safe because selectLibrary runs on the car's callback thread and not on
        // this class's IO scope. Moving this body into a coroutine means posting
        // invalidateRoot() as well.
        BrowseTreeInvalidator.invalidateRoot()
        BrowseTreeInvalidator.invalidateNode(
            Constants.PICK_SERVER_ID + machineIdentifier,
            0
        )

        future.set(LibraryResult.ofItemList(ImmutableList.of(confirmationRow(payload)), null))
        return future
    }

    /**
     * The row [selectLibrary] answers with, rebuilt from its payload so that a
     * tap on it can return the row itself rather than an empty list -- see
     * `MediaBrowserTree.getChildren`.
     *
     * Not a `MediaBrowserTree.getItem` lookup: the tree's static nodes are only
     * ever the ones `buildTree()` registers, so this row's id would never
     * resolve there. [libraryNames] is the only place this name lives; the
     * fallback covers a process restart between listing the libraries and
     * tapping one, when the map is empty again.
     */
    internal fun confirmationRow(payload: String): MediaItem {
        val sectionKey = payload.substringAfter('|', "")
        val name = libraryNames[payload] ?: "Library $sectionKey"

        // The server name comes from the held candidate rather than a second
        // map: the payload already carries the machine identifier, and the
        // candidate is what selectLibrary just committed from. It names the
        // server because a library called "Music" is ambiguous across an
        // account -- most servers have one.
        val serverName = candidates[payload.substringBefore('|')]?.second?.name
        val context = App.getContext()
        return browsableRow(
            mediaId = Constants.PICK_LIBRARY_ID + payload + CONFIRMED_SUFFIX,
            // Browsable purely so the car draws it: an item with neither
            // isBrowsable nor isPlayable set is dropped from the list entirely.
            title = if (serverName.isNullOrBlank()) {
                // Reachable after a process restart between listing the
                // libraries and tapping one, when the candidate is gone.
                context.getString(R.string.browse_now_browsing_no_server, name)
            } else {
                context.getString(R.string.browse_now_browsing, name, serverName)
            },
            // Info, not warning: this row reports a selection that succeeded.
            iconRes = R.drawable.ic_browse_info
        )
    }

    /**
     * Test seam only. A live [getLibraries] is the only production writer of
     * [candidates] and [libraryNames], and it needs a real plex.tv round trip
     * plus a server probe -- neither available under Robolectric. Tests use
     * this to put a fresh repository into the state [getLibraries] would have
     * left it in, so [selectLibrary] can be exercised without restructuring
     * its signature or widening either field's visibility beyond this seam.
     *
     * Additive, like the production writer: calling it twice records two
     * servers rather than replacing the first.
     */
    @VisibleForTesting
    internal fun primeCandidateForTest(
        uri: String,
        resource: Resource,
        sectionKey: String,
        libraryName: String
    ) {
        val machineIdentifier = requireNotNull(resource.clientIdentifier) {
            "test resource needs a clientIdentifier"
        }
        candidates[machineIdentifier] = uri to resource
        libraryNames[libraryIdPayload(machineIdentifier, sectionKey)] = libraryName
    }

    companion object {

        data class ServerRow(val machineIdentifier: String?, val name: String)

        /**
         * Marks the row [selectLibrary] answers with, so tapping it is
         * recognisable as "show me that row again" rather than "commit this
         * library" a second time.
         */
        const val CONFIRMED_SUFFIX = ":confirmed"

        /** U+2713 CHECK MARK. The tick is the only signal of which library is in use. */
        private const val TICK = "✓ "

        @JvmStatic
        /** Shared by the server rows and the library rows inside one. */
        fun rowTitle(name: String, current: Boolean): String =
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

        /**
         * A single browsable row that says why a picker list is otherwise
         * empty.
         *
         * **Not** a `LibraryResult.ofError`. media3 1.9.2 replicates exactly two
         * SessionError codes to a legacy MediaBrowserCompat client -- -102 and
         * -105, see [CarSignInResolution] -- and `com.android.car.media` is such
         * a client. Every other code arrives at
         * `MediaLibraryServiceLegacyStub.onLoadChildren` as a null result, so
         * the message never leaves the process and the car draws its own generic
         * failure. A row is the only way a sentence reaches this screen, and it
         * is the same trick the confirmation row plays.
         *
         * Browsable, never playable, like every other row here. The message
         * rides in the media id so a tap can return the same row (see
         * `MediaBrowserTree.getChildren`) without a map to keep in sync.
         *
         * [subtitle] is optional and, unlike [message], does **not** ride in
         * the media id -- `MediaBrowserTree.getChildren`'s `PICK_MESSAGE_ID`
         * branch rebuilds this row from the id alone when it is tapped, so a
         * subtitle passed here is lost on that round trip. Accepted rather than
         * worked around: the row is a dead end however it is reached, the
         * subtitle is a hint rather than information the user needs back, and
         * encoding a second string into the media id to survive a tap on a row
         * that goes nowhere would cost more than the hint is worth.
         */
        @JvmStatic
        @JvmOverloads
        fun messageRow(message: String, subtitle: String? = null): MediaItem =
            browsableRow(
                mediaId = Constants.PICK_MESSAGE_ID + message,
                title = message,
                // Warning rather than info for all four of these: every one is a
                // dead end -- plex.tv unreachable, server gone, server
                // unreachable, no music libraries on it -- so none of them lets
                // the user pick anything here.
                //
                // Baked in rather than passed per message, which keeps the id
                // round trip above honest: MediaBrowserTree rebuilds this row
                // from the id alone when it is tapped, so a per-message severity
                // would have to be encoded into the id to survive that.
                iconRes = R.drawable.ic_browse_warning,
                subtitle = subtitle
            )

        /**
         * [iconRes] is worth passing wherever the row stands for a real thing.
         * A row with no artwork gets the car's own placeholder, which is a music
         * note on a colour picked per row -- fine for a music library, wrong for
         * a Plex server, and noisy either way since the colour carries no
         * meaning.
         *
         * [subtitle] is the browse list's second line, the same one an album
         * uses for its artist -- see `MediaBrowserTree.signedOutRow`, which uses
         * it the same way. Absent by default: most rows here are a single line.
         */
        @JvmStatic
        @JvmOverloads
        fun browsableRow(
            mediaId: String,
            title: String,
            iconRes: Int? = null,
            subtitle: String? = null
        ): MediaItem =
            MediaItem.Builder()
                .setMediaId(mediaId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(subtitle)
                        .setIsBrowsable(true)
                        // Never playable: a playable row opens Now Playing on tap
                        // and nothing the app returns can suppress that.
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .setArtworkUri(iconRes?.let { ResourceUris.forResource(it) })
                        .build()
                )
                .build()
    }
}
