package com.cappielloantonio.tempo.plex.api.auth

import android.util.Log
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexRetrofitFactory
import com.cappielloantonio.tempo.plex.models.Pin
import com.cappielloantonio.tempo.plex.models.Resource
import retrofit2.Call
import java.time.Instant
import java.time.format.DateTimeParseException

private const val TAG = "AuthClient"

/**
 * The plex.tv half of the API: create a PIN, poll it, then discover which servers
 * the account can reach.
 */
class AuthClient(api: PlexApi) {

    private val service: AuthService =
        PlexRetrofitFactory.plexTv(api).create(AuthService::class.java)

    fun createPin(): Call<Pin> {
        Log.d(TAG, "createPin()")
        return service.createPin()
    }

    fun getPin(pinId: Long): Call<Pin> = service.getPin(pinId)

    fun getResources(): Call<List<Resource>> {
        Log.d(TAG, "getResources()")
        return service.getResources()
    }

    companion object {

        /**
         * Picks the address to talk to a server on. A server advertises several:
         * prefer a LAN address, then a direct remote one, and use Plex's relay
         * only as a last resort since it is bandwidth-limited.
         */
        @JvmStatic
        fun bestConnectionUri(resource: Resource): String? {
            val usable = resource.connections
                ?.filter { !it.uri.isNullOrBlank() }
                ?: return null

            return usable.firstOrNull { it.local == true && it.relay != true }?.uri
                ?: usable.firstOrNull { it.relay != true }?.uri
                ?: usable.firstOrNull()?.uri
        }

        /**
         * Plex reports pin expiry as ISO-8601. Converted here rather than in
         * PlexPinState so that state machine stays a pure function over primitives.
         */
        @JvmStatic
        fun expiresAtEpochSeconds(pin: Pin): Long? {
            val raw = pin.expiresAt ?: return null
            return try {
                Instant.parse(raw).epochSecond
            } catch (e: DateTimeParseException) {
                Log.d(TAG, "unparseable pin expiry: $raw", e)
                null
            }
        }
    }
}
