package com.cappielloantonio.tempo.ui.fragment

import android.util.Log
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.api.library.LibraryClient
import com.cappielloantonio.tempo.plex.api.server.ServerAddressBook
import com.cappielloantonio.tempo.plex.api.server.ServerProbe
import com.cappielloantonio.tempo.plex.models.Resource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

private const val TAG = "DebugServerSource"

/**
 * Everything the Server tab fetches when it opens, behind one seam so its
 * Robolectric tests touch no network: the live tab talks to plex.tv and to
 * every address the server advertises.
 */
internal interface DebugServerSource {
    /** plex.tv's listing of the session's server, null when unavailable; see ServerAddressBook.refresh(). */
    suspend fun refresh(): Resource?

    /** The session library's name, or null when the server does not say. */
    suspend fun libraryName(): String?

    suspend fun check(uri: String): ServerProbe.Check
}

internal object LiveDebugServerSource : DebugServerSource {
    private val probe by lazy { ServerProbe() }

    override suspend fun refresh(): Resource? = ServerAddressBook.shared.refresh()

    /**
     * The wide catch is ServerAddressBook.refresh()'s: the caller is a view's
     * coroutine with no handler, and plexCall catches only IOException and
     * HttpException, so a malformed sections response (JsonSyntaxException) or
     * an unparseable stored base URL (IllegalArgumentException) would kill the
     * process and playback with it. No either { } here; cancellation is
     * rethrown.
     *
     * Bounded because the address in use may be the dead one this tab is
     * diagnosing, and the server client would wait a minute on it. Not routed
     * through address recovery: a debug screen must not switch addresses.
     */
    override suspend fun libraryName(): String? =
        try {
            withTimeout(LIBRARY_NAME_TIMEOUT_MS) {
                val api = PlexApi()
                val key = api.session?.musicSectionKey?.value
                val sections = key?.let { LibraryClient(api).getSections().getOrNull() }
                sections?.let { LibraryClient.musicSections(it).firstOrNull { section -> section.key == key }?.title }
            }
        } catch (e: TimeoutCancellationException) {
            // Must stay ahead of the rethrow below: this is a CancellationException
            // subclass, and rethrowing it would end the caller's coroutine.
            Log.d(TAG, "library name lookup timed out")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "library name lookup failed unexpectedly", t)
            null
        }

    override suspend fun check(uri: String): ServerProbe.Check = probe.check(uri)

    private const val LIBRARY_NAME_TIMEOUT_MS = 5_000L
}
