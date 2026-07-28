package com.cappielloantonio.tempo.plex.api.auth

import com.cappielloantonio.tempo.plex.models.Pin
import com.cappielloantonio.tempo.plex.models.Resource
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * plex.tv endpoints. These work while signed out -- that is the point of the PIN
 * flow -- so they must never be issued against the media server instance.
 *
 * Responses here are bare JSON with no MediaContainer envelope.
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
    fun createPin(): Call<Pin>

    @GET("pins/{pinId}")
    fun getPin(@Path("pinId") pinId: Long): Call<Pin>

    @GET("resources")
    fun getResources(
        @Query("includeHttps") includeHttps: Int = 1,
        @Query("includeRelay") includeRelay: Int = 1
    ): Call<List<Resource>>
}
