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

    /** strong=true asks for a longer, less guessable code. */
    @POST("pins")
    fun createPin(@Query("strong") strong: Boolean = true): Call<Pin>

    @GET("pins/{pinId}")
    fun getPin(@Path("pinId") pinId: Long): Call<Pin>

    @GET("resources")
    fun getResources(
        @Query("includeHttps") includeHttps: Int = 1,
        @Query("includeRelay") includeRelay: Int = 1
    ): Call<List<Resource>>
}
