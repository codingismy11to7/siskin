package com.cappielloantonio.tempo.plex.auth

/**
 * What a poll of GET /pins/{pinId} means.
 *
 * Kept as a pure function over primitives so the sign-in screen's polling logic
 * can be tested without a network or an Android framework class in sight.
 */
sealed interface PlexPinState {

    data object Pending : PlexPinState

    data class Authorized(val authToken: String) : PlexPinState

    data object Expired : PlexPinState

    companion object {
        @JvmStatic
        fun evaluate(
            authToken: String?,
            expiresAtEpochSeconds: Long?,
            nowEpochSeconds: Long
        ): PlexPinState {
            // A token that arrived outranks the clock: the poll already succeeded.
            if (!authToken.isNullOrBlank()) return Authorized(authToken)

            // Never expire a pin we cannot date -- the caller bounds the poll loop.
            if (expiresAtEpochSeconds == null) return Pending

            return if (nowEpochSeconds >= expiresAtEpochSeconds) Expired else Pending
        }
    }
}
