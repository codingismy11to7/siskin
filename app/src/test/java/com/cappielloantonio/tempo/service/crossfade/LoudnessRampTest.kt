package com.cappielloantonio.tempo.service.crossfade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessRampTest {
    @Test
    fun parsesPointsInOrderOfSeconds() {
        assertEquals(
            listOf(RampPoint(-51f, 0.17f), RampPoint(-9f, 6.47f)),
            LoudnessRamp.parse("-9.00 6.47;-51.00 0.17;"),
        )
    }

    @Test
    fun skipsMalformedPointsAndToleratesNothing() {
        assertEquals(listOf(RampPoint(-9f, 6.47f)), LoudnessRamp.parse("x;-9.00 6.47;1 2 3;;"))
        assertTrue(LoudnessRamp.parse(null).isEmpty())
        assertTrue(LoudnessRamp.parse("").isEmpty())
    }

    @Test
    fun interpolatesTheThresholdCrossing() {
        val points = LoudnessRamp.parse("-5.07 3.31;-2.38 3.61;")
        // 3.31 + 0.30 * (−3 − −5.07) / (−2.38 − −5.07) = 3.5409
        assertEquals(3.541, LoudnessRamp.secondsAt(points, -3f)!!.toDouble(), 0.001)
    }

    @Test
    fun aFirstPointAlreadyAboveTheThresholdIsTheCrossing() {
        assertEquals(1.0f, LoudnessRamp.secondsAt(listOf(RampPoint(0f, 1f)), -3f))
    }

    @Test
    fun aRampThatNeverReachesTheThresholdHasNoCrossing() {
        assertNull(LoudnessRamp.secondsAt(listOf(RampPoint(-20f, 1f), RampPoint(-10f, 5f)), -3f))
    }

    @Test
    fun overlapIsTheCrossingClamped() {
        // Real end ramps from jorge: an abrupt ending, a 12.7 s fade, a typical one.
        assertEquals(2_000L, LoudnessRamp.overlapMs("-51.00 0.19;-9.00 0.59;-1.53 0.69;1.57 0.79;"))
        assertEquals(10_000L, LoudnessRamp.overlapMs("-5.82 11.01;-2.96 12.71;0.15 15.01;"))
        assertEquals(3_540L, LoudnessRamp.overlapMs("-5.07 3.31;-2.38 3.61;"))
    }

    @Test
    fun noRampMeansNoOverlap() {
        assertNull(LoudnessRamp.overlapMs(null))
        assertNull(LoudnessRamp.overlapMs("-40.00 1.00;"))
    }
}
