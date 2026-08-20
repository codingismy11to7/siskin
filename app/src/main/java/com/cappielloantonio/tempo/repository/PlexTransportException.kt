package com.cappielloantonio.tempo.repository

import com.cappielloantonio.tempo.plex.PlexTransportFailure
import java.io.IOException

/**
 * Carries a non-HTTP [PlexTransportFailure] across media3's ListenableFuture
 * boundary.
 *
 * The failure is a value everywhere else in this layer. It only becomes a
 * throwable here because `SettableFuture.setException` takes a `Throwable` and
 * nothing else, and MediaLibrarySessionCallback distinguishes "unreachable" from
 * "rejected" by whether the future completed exceptionally. Extends IOException
 * so anything already treating an exceptionally-completed browse future as a
 * network problem keeps working.
 */
class PlexTransportException(val failure: PlexTransportFailure) : IOException(failure.toString())
