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
        // Comfortably clear of the hard cap, with a non-null expiry still out
        // ahead -- true here can only come from the expiry check.
        assertTrue(PlexPinState.shouldKeepPolling(1000L, 1030L, 1900L))

        // One second before expiry: true here is possible only because line C's
        // `<` says so, not because of the hard cap (elapsed is far under it).
        assertTrue(PlexPinState.shouldKeepPolling(1000L, 1049L, 1050L))
    }

    @Test
    fun stopsPollingOnceThePinExpires() {
        // Elapsed time (50s) is kept well under HARD_CAP_SECONDS (900s) so the
        // hard-cap check cannot be the reason this returns false; only the
        // expiry comparison can produce false here, which is what this test
        // is meant to isolate.
        assertFalse(PlexPinState.shouldKeepPolling(1000L, 1050L, 1050L))
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

    @Test
    fun thePollStartsFastWhileApprovalIsPlausible() {
        // The window nearly every completed approval lands in. Unchanged from
        // the flat 2s cadence this ladder replaces -- the whole point is that
        // the responsive case cannot tell the difference.
        assertEquals(2_000L, PlexPinState.pollDelayMillis(0L))
        assertEquals(2_000L, PlexPinState.pollDelayMillis(59L))
    }

    @Test
    fun theIntervalWidensAfterAMinute() {
        // 60 is the boundary, and it belongs to the slower step.
        assertEquals(5_000L, PlexPinState.pollDelayMillis(60L))
        assertEquals(5_000L, PlexPinState.pollDelayMillis(179L))
    }

    @Test
    fun theTailIsSlow() {
        // Past three minutes nobody is coming. 15s from here to the hard cap is
        // 47 polls where a flat 2s was 360, which is where the saving lives.
        assertEquals(15_000L, PlexPinState.pollDelayMillis(180L))
        assertEquals(15_000L, PlexPinState.pollDelayMillis(PlexPinState.HARD_CAP_SECONDS - 1))
    }
}
