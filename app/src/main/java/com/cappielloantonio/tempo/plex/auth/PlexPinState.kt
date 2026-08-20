package com.cappielloantonio.tempo.plex.auth

/**
 * What a poll of GET /pins/{pinId} means.
 *
 * Kept as a pure function over primitives so the sign-in screen's polling logic
 * can be tested without a network or an Android framework class in sight.
 */
sealed interface PlexPinState {
    data object Pending : PlexPinState

    data class Authorized(
        val authToken: String,
    ) : PlexPinState

    data object Expired : PlexPinState

    companion object {
        @JvmStatic
        fun evaluate(
            authToken: String?,
            expiresAtEpochSeconds: Long?,
            nowEpochSeconds: Long,
        ): PlexPinState {
            // A token that arrived outranks the clock: the poll already succeeded.
            if (!authToken.isNullOrBlank()) return Authorized(authToken)

            // Never expire a pin we cannot date -- the caller bounds the poll loop.
            if (expiresAtEpochSeconds == null) return Pending

            return if (nowEpochSeconds >= expiresAtEpochSeconds) Expired else Pending
        }

        /** Plex issues pins with a 15-minute life; this backstops an unparseable expiry. */
        const val HARD_CAP_SECONDS = 900L

        /**
         * Bounds the poll loop [evaluate] deliberately does not bound.
         *
         * A pin whose expiry cannot be parsed makes evaluate return Pending
         * forever. The cap outranks the server-supplied expiry in both
         * directions: it stops an undated pin, and it stops a pin whose expiry
         * is implausibly far out.
         */
        @JvmStatic
        fun shouldKeepPolling(
            startedAtEpochSeconds: Long,
            nowEpochSeconds: Long,
            expiresAtEpochSeconds: Long?,
        ): Boolean {
            if (nowEpochSeconds - startedAtEpochSeconds >= HARD_CAP_SECONDS) return false
            if (expiresAtEpochSeconds == null) return true
            return nowEpochSeconds < expiresAtEpochSeconds
        }

        /**
         * How long to wait before the next poll, given how long the pin has been alive.
         *
         * A flat 2s for the pin's whole 15-minute life cost ~450 requests for a
         * sign-in nobody completes, and nobody completing it is a routine case:
         * CarHostActivity is the media session's activity, so the car can open
         * the sign-in screen by accident. The fast cadence only ever earns its
         * keep in the seconds between approving on a phone and the car noticing,
         * and that window is early -- so the tail slows down and the responsive
         * case is untouched. 101 requests instead of 450.
         *
         * Keyed on elapsed time rather than poll count deliberately. A dropped
         * poll `continue`s without ever reaching plex.tv, so counting requests
         * would widen the interval fastest on exactly the flaky connections that
         * can least afford it.
         */
        @JvmStatic
        fun pollDelayMillis(elapsedSeconds: Long): Long =
            when {
                elapsedSeconds < 60L -> 2_000L
                elapsedSeconds < 180L -> 5_000L
                else -> 15_000L
            }
    }
}
