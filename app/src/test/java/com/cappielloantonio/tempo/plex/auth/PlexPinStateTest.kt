package com.cappielloantonio.tempo.plex.auth

import org.junit.Assert.assertEquals
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
}
