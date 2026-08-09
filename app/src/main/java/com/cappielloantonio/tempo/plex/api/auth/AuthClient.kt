package com.cappielloantonio.tempo.plex.api.auth

import android.util.Log
import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.PlexHost
import com.cappielloantonio.tempo.plex.PlexRetrofitFactory
import com.cappielloantonio.tempo.plex.PlexTransportFailure
import com.cappielloantonio.tempo.plex.api.server.ServerProbe
import com.cappielloantonio.tempo.plex.models.Pin
import com.cappielloantonio.tempo.plex.models.Resource
import com.cappielloantonio.tempo.plex.plexCall
import java.time.Instant
import java.time.format.DateTimeParseException

private const val TAG = "AuthClient"

/**
 * The plex.tv half of the API: create a PIN, poll it, then discover which servers
 * the account can reach.
 *
 * Every call is a plex.tv call, so every failure carries [PlexHost.PlexTv].
 */
class AuthClient(api: PlexApi) {

    private val service: AuthService =
        PlexRetrofitFactory.plexTv(api).create(AuthService::class.java)

    /**
     * Validated on the way out, so callers get a PIN they can use rather than one
     * they have to re-check.
     */
    suspend fun createPin(): Either<CreatePinError, CreatedPin> = either {
        Log.d(TAG, "createPin()")
        val pin = plexCall(PlexHost.PlexTv) { service.createPin() }
            .mapLeft(CreatePinError::Transport)
            .bind()
        validate(pin).bind()
    }

    /** Unvalidated on purpose: the poll only reads [Pin.authToken] and the expiry. */
    suspend fun getPin(pinId: Long): Either<PlexTransportFailure, Pin> =
        plexCall(PlexHost.PlexTv) { service.getPin(pinId) }

    suspend fun getResources(): Either<PlexTransportFailure, List<Resource>> {
        Log.d(TAG, "getResources()")
        return plexCall(PlexHost.PlexTv) { service.getResources() }
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
         * Turns a wire [Pin] into a [CreatedPin], or reports what plex.tv omitted.
         *
         * Separated from [createPin] so the refinement is a pure function that can
         * be tested without a network.
         */
        @JvmStatic
        fun validate(pin: Pin): Either<CreatePinError, CreatedPin> = either {
            val id = ensureNotNull(pin.id) { CreatePinError.NoPinCode }
            val code = ensureNotNull(pin.code?.takeIf { it.isNotBlank() }) {
                CreatePinError.NoPinCode
            }
            CreatedPin(
                id = id,
                code = code,
                qrUrl = pin.qr?.takeIf { it.isNotBlank() },
                expiresAtEpochSeconds = expiresAtEpochSeconds(pin)
            )
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
