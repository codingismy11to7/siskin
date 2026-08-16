package com.cappielloantonio.tempo.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plain JUnit: this is string work with no Android in it, which is the point
 * of it being its own object rather than two call sites in a provider and a
 * mapper that have to agree about a delimiter.
 */
class HubCoverPoolTest {

    private val thumbs = listOf(
        "/library/metadata/51/thumb/1699999999",
        "/library/metadata/77/thumb/1700000000",
        "/library/metadata/92/thumb/1700000001"
    )

    @Test
    fun aPoolSurvivesTheRoundTrip() {
        assertEquals(thumbs, HubCoverPool.decode(HubCoverPool.encode(thumbs)))
    }

    @Test
    fun oneThumbIsStillAPool() {
        val one = listOf(thumbs.first())
        assertEquals(one, HubCoverPool.decode(HubCoverPool.encode(one)))
    }

    @Test
    fun sixThumbsSurviveTheRoundTrip() {
        val six = (1..6).map { "/library/metadata/$it/thumb/17000000$it" }
        assertEquals(six, HubCoverPool.decode(HubCoverPool.encode(six)))
    }

    @Test
    fun aThumbCarryingTheDelimiterSplitsIntoPartsTheProviderRefuses() {
        // Deliberately not repaired here. decode keeps every component exactly
        // as it found it, so a pool that cannot be a pool arrives as components
        // that fail isServerRelativePath and costs that row its tile. Guessing
        // which halves belonged together would be the only alternative, and a
        // real Plex thumb -- digits and slashes -- never contains a comma.
        assertEquals(
            listOf("/library/metadata/5", "1/thumb/1"),
            HubCoverPool.decode(HubCoverPool.encode(listOf("/library/metadata/5,1/thumb/1")))
        )
    }

    @Test
    fun anEmptySegmentDecodesToOneBlankComponentRatherThanToNothing() {
        // Pins which of the provider's two checks refuses it: the per-element
        // isServerRelativePath, not the emptiness check. Either refuses it, and
        // a reader should not have to guess which.
        assertEquals(listOf(""), HubCoverPool.decode(""))
    }
}
