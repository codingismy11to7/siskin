package com.cappielloantonio.tempo.plex.api.auth

import com.cappielloantonio.tempo.plex.models.Pin
import com.cappielloantonio.tempo.plex.models.Resource
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * plex.tv endpoints. These work while signed out -- that is the point of the PIN
 * flow -- so they must never be issued against the media server instance.
 *
 * Responses here are bare JSON with no MediaContainer envelope.
 *
 * A non-2xx still throws `HttpException` rather than arriving as a body to
 * inspect -- Retrofit's behaviour, unchanged -- but that exception no longer
 * reaches a call site directly. `AuthClient` wraps every call in `plexCall`,
 * which catches it and returns `Either<PlexFailure, T>` instead; `PlexFailure`
 * is what now keeps "the server said no" and "I could not reach it" apart,
 * rather than both showing up as a null body.
 */
interface AuthService {

    /**
     * No `strong` query param: that asks for a 25-character code, but this
     * screen's copy promises one typable at plex.tv/link as an alternative to
     * scanning, and nobody is typing 25 characters into a phone. The default
     * (short, 4-character) code is acceptable because the grant it produces is
     * bound to this install's `X-Plex-Client-Identifier` -- guessing it is not
     * enough to steal the sign-in.
     */
    @POST("pins")
    suspend fun createPin(): Pin

    @GET("pins/{pinId}")
    suspend fun getPin(@Path("pinId") pinId: Long): Pin

    @GET("resources")
    suspend fun getResources(
        @Query("includeHttps") includeHttps: Int = 1,
        @Query("includeRelay") includeRelay: Int = 1
    ): List<Resource>
}
