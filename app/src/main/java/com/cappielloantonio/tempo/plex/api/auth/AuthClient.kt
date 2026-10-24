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
import com.cappielloantonio.tempo.plex.models.HomeUser
import com.cappielloantonio.tempo.plex.models.Pin
import com.cappielloantonio.tempo.plex.models.PlexAccount
import com.cappielloantonio.tempo.plex.models.PlexErrorBody
import com.cappielloantonio.tempo.plex.models.Resource
import com.cappielloantonio.tempo.plex.plexCall
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.time.Instant
import java.time.format.DateTimeParseException

private const val TAG = "AuthClient"

/**
 * The plex.tv half of the API: create a PIN, poll it, then discover which servers
 * the account can reach.
 *
 * Every call is a plex.tv call, so every failure carries [PlexHost.PlexTv].
 */
class AuthClient(
    api: PlexApi,
    /** Non-null replaces the stored account token on every call; see [PlexRetrofitFactory.plexTv]. */
    token: String? = null,
) {
    private val service: AuthService =
        (if (token == null) PlexRetrofitFactory.plexTv(api) else PlexRetrofitFactory.plexTv(api, token))
            .create(AuthService::class.java)

    /**
     * Validated on the way out, so callers get a PIN they can use rather than one
     * they have to re-check.
     */
    suspend fun createPin(): Either<CreatePinError, CreatedPin> =
        either {
            Log.d(TAG, "createPin()")
            val pin =
                plexCall(PlexHost.PlexTv) { service.createPin() }
                    .mapLeft(CreatePinError::Transport)
                    .bind()
            validate(pin).bind()
        }

    /** Unvalidated on purpose: the poll only reads [Pin.authToken] and the expiry. */
    suspend fun getPin(pinId: Long): Either<PlexTransportFailure, Pin> = plexCall(PlexHost.PlexTv) { service.getPin(pinId) }

    suspend fun getResources(): Either<PlexTransportFailure, List<Resource>> {
        Log.d(TAG, "getResources()")
        return plexCall(PlexHost.PlexTv) { service.getResources() }
    }

    /** Members with no uuid are dropped: nothing could switch to them. */
    suspend fun homeUsers(): Either<PlexTransportFailure, List<HomeUser>> {
        Log.d(TAG, "homeUsers()")
        return plexCall(PlexHost.PlexTv) { service.getHomeUsers() }
            .map { home -> home.users.orEmpty().filter { !it.uuid.isNullOrBlank() } }
    }

    suspend fun currentUser(): Either<PlexTransportFailure, PlexAccount> = plexCall(PlexHost.PlexTv) { service.getUser() }

    /**
     * The error body is read inside [plexCall] because reading it is I/O and
     * can throw `IOException`, which must land in the transport failure
     * rather than escape this `either`.
     */
    suspend fun switchTo(
        uuid: String,
        pin: String?,
    ): Either<SwitchUserError, PlexAccount> =
        either {
            Log.d(TAG, "switchTo(protected=${pin != null})")
            val (code, body, errorBody) =
                plexCall(PlexHost.PlexTv) {
                    val response = service.switchHomeUser(uuid, pin)
                    Triple(response.code(), response.body(), response.errorBody()?.string())
                }.mapLeft(SwitchUserError::Transport).bind()

            if (code !in 200..299) raise(switchFailure(code, errorBody))
            ensureNotNull(body?.takeIf { !it.authToken.isNullOrBlank() }) {
                SwitchUserError.Transport(PlexTransportFailure.Http(PlexHost.PlexTv, code))
            }
        }

    companion object {
        private const val WRONG_PIN = 1041
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private val gson = Gson()

        /**
         * A refused switch, read from its status and plex.tv's error body.
         * Only 403 *with* code 1041 is a wrong PIN; any other refusal is an
         * HTTP failure, so an outage never reads as "wrong PIN".
         */
        @JvmStatic
        fun switchFailure(
            code: Int,
            errorBody: String?,
        ): SwitchUserError =
            when {
                code == HTTP_TOO_MANY_REQUESTS -> SwitchUserError.RateLimited
                code == HTTP_FORBIDDEN && WRONG_PIN in errorCodes(errorBody) -> SwitchUserError.WrongPin
                else -> SwitchUserError.Transport(PlexTransportFailure.Http(PlexHost.PlexTv, code))
            }

        private fun errorCodes(body: String?): List<Int> {
            if (body == null) return emptyList()
            return try {
                gson
                    .fromJson(body, PlexErrorBody::class.java)
                    ?.errors
                    .orEmpty()
                    .mapNotNull { it.code }
            } catch (e: JsonSyntaxException) {
                Log.d(TAG, "unreadable plex.tv error body", e)
                emptyList()
            }
        }

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
                val provides =
                    resource.provides
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
        fun validate(pin: Pin): Either<CreatePinError, CreatedPin> =
            either {
                val id = ensureNotNull(pin.id) { CreatePinError.NoPinCode }
                val code =
                    ensureNotNull(pin.code?.takeIf { it.isNotBlank() }) {
                        CreatePinError.NoPinCode
                    }
                CreatedPin(
                    id = id,
                    code = code,
                    qrUrl = pin.qr?.takeIf { it.isNotBlank() },
                    expiresAtEpochSeconds = expiresAtEpochSeconds(pin),
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
