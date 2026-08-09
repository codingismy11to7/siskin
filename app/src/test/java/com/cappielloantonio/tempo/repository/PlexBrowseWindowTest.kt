package com.cappielloantonio.tempo.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class PlexBrowseWindowTest {

    @Test
    fun shortTitlesAreLeftAlone() {
        assertEquals("Beck", PlexBrowseRepository.shortened("Beck"))
    }

    @Test
    fun aTitleExactlyAtTheLimitIsLeftAlone() {
        val exact = "1234567890123456" // 16 characters
        assertEquals(exact, PlexBrowseRepository.shortened(exact))
    }

    @Test
    fun longTitlesAreCutWithAnEllipsis() {
        // Both ends of a range must fit one row; the car truncates from the
        // right, which would cost the second title entirely.
        assertEquals("A State of Tran…", PlexBrowseRepository.shortened("A State of Trance Classics, Vol. 2"))
    }

    @Test
    fun theCutDoesNotLeaveATrailingSpaceBeforeTheEllipsis() {
        assertEquals("A State of the…", PlexBrowseRepository.shortened("A State of the Art Recording"))
    }
}
