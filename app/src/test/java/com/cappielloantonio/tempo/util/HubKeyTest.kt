package com.cappielloantonio.tempo.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plain JUnit: the encoding is pure string work and touches no framework class,
 * so nothing here can be hollowed out by unitTests.returnDefaultValues.
 */
class HubKeyTest {
    @Test
    fun roundTripsAKeyThroughItsPayload() {
        val key = "/library/sections/7/all?type=9&genre=138884"
        assertEquals(key, HubKey.keyIn(HubKey.of("abc123-7", key)))
    }

    @Test
    fun keepsTheQueryIntactIncludingComparisonOperators() {
        val key = "/library/sections/7/all?type=8&viewCount>=50&lastViewedAt<=-5mon&sort=random"
        assertEquals(key, HubKey.keyIn(HubKey.of("abc123-7", key)))
    }

    @Test
    fun aPayloadWithNoSeparatorIsItsOwnKey() {
        assertEquals("/hubs/sections/7/popular", HubKey.keyIn("/hubs/sections/7/popular"))
    }

    @Test
    fun splitsOnTheFirstPipeBecauseTheKeyItselfMayContainOne() {
        val key = "/library/sections/7/all?type=9&studio=A|B"

        assertEquals(key, HubKey.keyIn(HubKey.of("abc123-7", key)))
    }

    @Test
    fun scopeInReadsTheHalfBeforeTheFirstPipe() {
        val key = "/library/sections/7/all?type=9&studio=A|B"

        assertEquals("abc123-7", HubKey.scopeIn(HubKey.of("abc123-7", key)))
    }

    @Test
    fun aPayloadWithNoSeparatorHasNoScope() {
        assertEquals(null, HubKey.scopeIn("/hubs/sections/7/popular"))
    }
}
