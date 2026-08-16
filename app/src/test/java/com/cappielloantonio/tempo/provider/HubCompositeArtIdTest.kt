package com.cappielloantonio.tempo.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JUnit: MessageDigest is plain JVM, so the naming half of this feature
 * is testable without Robolectric.
 */
class HubCompositeArtIdTest {

    private val pool = listOf(
        "/library/metadata/51/thumb/1699999999",
        "/library/metadata/77/thumb/1700000000"
    )

    @Test
    fun anIdIsHexAndFixedWidthSoItCanBeAFilename() {
        // The property that lets this path skip the charset guard the decade
        // path needs: the id is derived, not received, and hex cannot carry a
        // separator, a `..`, or a length to play with.
        assertTrue(HubCompositeArt.idFor(pool).matches(Regex("[0-9a-f]{16}")))
    }

    @Test
    fun theSamePoolGetsTheSameId() {
        assertEquals(HubCompositeArt.idFor(pool), HubCompositeArt.idFor(pool.toList()))
    }

    @Test
    fun aDifferentPoolGetsADifferentId() {
        assertNotEquals(
            HubCompositeArt.idFor(pool),
            HubCompositeArt.idFor(pool + "/library/metadata/92/thumb/1700000001")
        )
    }

    @Test
    fun orderIsPartOfTheIdBecauseItIsPartOfTheTile() {
        // Cells are filled in pool order, so two orderings are two images.
        assertNotEquals(HubCompositeArt.idFor(pool), HubCompositeArt.idFor(pool.reversed()))
    }

    @Test
    fun aHostilePoolStillProducesAFilenameSafeId() {
        // Reached only if the provider's guards were bypassed, which is the
        // point: nothing caller-shaped survives the digest.
        assertTrue(
            HubCompositeArt.idFor(listOf("../../evil", "/x\\y")).matches(Regex("[0-9a-f]{16}"))
        )
    }
}
