package com.cappielloantonio.tempo.provider

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The candidate walk, driven by a stub loader rather than Glide.
 *
 * This is the behaviour the hub path exists to get: the decade path picks its
 * four covers and fails the whole tile if one of them fails to load, and its
 * OVER_FETCH spares never help because they are discarded before the fetch.
 * Walking the pool instead means the spares are what a failure costs.
 *
 * Plain JUnit: no Android, no Glide, no bitmaps -- which is the reason `pick`
 * is a generic function over a loader rather than four lines inside a build
 * body that only Robolectric could reach and that `returnDefaultValues` would
 * let pass while measuring nothing.
 */
class CompositeArtPickTest {

    private val pool = listOf("a", "b", "c", "d", "e", "f")

    /** Loads everything except the named failures, echoing the thumb back. */
    private fun loader(vararg failing: String): (String) -> String? =
        { thumb -> thumb.takeUnless { it in failing } }

    @Test
    fun aCleanPoolYieldsTheFirstFour() {
        assertEquals(listOf("a", "b", "c", "d"), CompositeArt.pick(pool, 4, loader()))
    }

    @Test
    fun twoFailuresStillYieldFourCovers() {
        // The whole point: the row still wears a full 2x2.
        assertEquals(listOf("c", "d", "e", "f"), CompositeArt.pick(pool, 4, loader("a", "b")))
    }

    @Test
    fun aPoolTooSmallToFillTheGridYieldsWhatItHas() {
        assertEquals(listOf("a", "b"), CompositeArt.pick(listOf("a", "b"), 4, loader()))
    }

    @Test
    fun everyFailureYieldsNothing() {
        assertEquals(emptyList<String>(), CompositeArt.pick(pool, 4, loader(*pool.toTypedArray())))
    }

    @Test
    fun itStopsLoadingOnceItHasEnough() {
        // Laziness is not a detail here: a cover fetch is a network round trip
        // through Glide, and loading the fifth and sixth candidates after four
        // have landed would spend two of them per tile for nothing.
        val attempted = mutableListOf<String>()
        CompositeArt.pick(pool, 4) { thumb -> attempted.add(thumb); thumb }
        assertEquals(listOf("a", "b", "c", "d"), attempted)
    }

    @Test
    fun wantingNothingLoadsNothing() {
        // Sequence.take(0) already short-circuits before the loader runs, so
        // this passes identically with or without the `if (want <= 0)` guard
        // in pick -- it does not exercise that branch. What the guard actually
        // stops is `take(-1)`, which throws; this test just pins the boundary
        // input's behaviour, not the guard's necessity.
        val attempted = mutableListOf<String>()
        val picked = CompositeArt.pick(pool, 0) { thumb -> attempted.add(thumb); thumb }
        assertEquals(emptyList<String>(), picked)
        assertEquals(emptyList<String>(), attempted)
    }
}
