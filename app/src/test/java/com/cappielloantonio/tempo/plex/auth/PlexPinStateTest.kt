package com.cappielloantonio.tempo.plex.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexPinStateTest {

    private val now = 1_000_000L

    @Test
    fun aClaimedPinIsAuthorized() {
        val state = PlexPinState.evaluate("tok123", now + 60, now)
        assertTrue(state is PlexPinState.Authorized)
        assertEquals("tok123", (state as PlexPinState.Authorized).authToken)
    }

    @Test
    fun anUnclaimedPinIsPending() {
        assertEquals(PlexPinState.Pending, PlexPinState.evaluate(null, now + 60, now))
    }

    @Test
    fun aBlankTokenCountsAsUnclaimed() {
        // Plex returns authToken: null while pending, but an empty string has been
        // observed in the wild; treating it as claimed would store a useless token.
        assertEquals(PlexPinState.Pending, PlexPinState.evaluate("   ", now + 60, now))
    }

    @Test
    fun anExpiredUnclaimedPinIsExpired() {
        assertEquals(PlexPinState.Expired, PlexPinState.evaluate(null, now - 1, now))
    }

    @Test
    fun expiryAtTheExactSecondCountsAsExpired() {
        assertEquals(PlexPinState.Expired, PlexPinState.evaluate(null, now, now))
    }

    @Test
    fun aClaimedPinWinsOverExpiry() {
        // If the token arrived, the poll succeeded; the clock is not a reason to discard it.
        val state = PlexPinState.evaluate("tok123", now - 100, now)
        assertTrue(state is PlexPinState.Authorized)
    }

    @Test
    fun anUnknownExpiryStaysPending() {
        // Never expire something we cannot date; the caller bounds the poll loop.
        assertEquals(PlexPinState.Pending, PlexPinState.evaluate(null, null, now))
    }

    @Test
    fun pollsWhileThePinIsStillAlive() {
        assertTrue(PlexPinState.shouldKeepPolling(1000L, 1030L, 1900L))
    }

    @Test
    fun stopsPollingOnceThePinExpires() {
        assertFalse(PlexPinState.shouldKeepPolling(1000L, 1900L, 1900L))
    }

    @Test
    fun stopsPollingAtTheHardCapWhenTheExpiryCannotBeDated() {
        // evaluate() returns Pending forever for a pin it cannot date -- its KDoc
        // says the caller bounds the loop. This is that bound. Without it the
        // sign-in screen would poll until the process died.
        assertTrue(PlexPinState.shouldKeepPolling(1000L, 1000L + 899L, null))
        assertFalse(PlexPinState.shouldKeepPolling(1000L, 1000L + 900L, null))
    }

    @Test
    fun theHardCapOutranksAGenerousExpiry() {
        // A server-supplied expiry an hour out must not extend the loop past the cap.
        assertFalse(PlexPinState.shouldKeepPolling(1000L, 1000L + 901L, 1000L + 3600L))
    }
}
