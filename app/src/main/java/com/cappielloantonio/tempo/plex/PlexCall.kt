package com.cappielloantonio.tempo.plex

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import retrofit2.HttpException
import java.io.IOException

/**
 * The one place Retrofit's exceptions become values.
 *
 * Catches exactly the two types Retrofit throws for a failed call and nothing
 * wider. A broad catch here would swallow `CancellationException` -- which on
 * the JVM extends `IllegalStateException` -- and a cancelled call would be
 * reported to the user as an unreachable server instead of vanishing quietly.
 * It would also capture genuine bugs in mapping code as transport failures.
 *
 * [host] is a fixed property of the calling client, never a per-call decision:
 * AuthClient is always plex.tv, LibraryClient and SearchClient are always the
 * media server, mirroring which PlexRetrofitFactory method built their service.
 */
internal suspend fun <T> plexCall(
    host: PlexHost,
    block: suspend () -> T
): Either<PlexFailure, T> =
    try {
        block().right()
    } catch (e: IOException) {
        PlexFailure.Unreachable(host).left()
    } catch (e: HttpException) {
        PlexFailure.Http(host, e.code()).left()
    }
