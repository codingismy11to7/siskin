package com.cappielloantonio.tempo.plex.api.auth

import android.util.Log
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexRetrofitFactory
import com.cappielloantonio.tempo.plex.models.Pin
import com.cappielloantonio.tempo.plex.models.Resource
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

    suspend fun createPin(): Pin {
        Log.d(TAG, "createPin()")
        return service.createPin()
    }

    suspend fun getPin(pinId: Long): Pin = service.getPin(pinId)

    suspend fun getResources(): List<Resource> {
        Log.d(TAG, "getResources()")
        return service.getResources()
    }

    companion object {

        /** Plex advertises capabilities as a comma-separated list. */
        private const val PROVIDES_SERVER = "server"

        /**
         * Narrows a /resources listing to media servers this app could actually
         * talk to. The endpoint also returns players, controllers and the
         * account's phones, and a server with no usable connection is no more
         * choosable than one that is absent.
         *
         * "Usable" here means *advertised*, not reachable: answering the stronger
         * question means probing every server in the account before the picker can
         * be drawn. Reachability is settled by [ServerProbe] once one is chosen.
         */
        @JvmStatic
        fun mediaServers(resources: List<Resource>?): List<Resource> =
            resources.orEmpty().filter { resource ->
                val provides = resource.provides
                    ?.split(",")
                    ?.map { it.trim() }
                    .orEmpty()
                provides.contains(PROVIDES_SERVER) && ServerProbe.hasUsableConnection(resource)
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
