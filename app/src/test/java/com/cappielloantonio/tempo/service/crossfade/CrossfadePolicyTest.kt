package com.cappielloantonio.tempo.service.crossfade

import androidx.media3.common.C
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrossfadePolicyTest {
    private val outgoing = TrackFadeInfo(4_000L, "album-a")
    private val incoming = TrackFadeInfo(3_000L, "album-b")

    private fun overlap(
        enabled: Boolean = true,
        hasNext: Boolean = true,
        repeatMode: Int = Player.REPEAT_MODE_OFF,
        durationMs: Long = 200_000L,
        outgoing: TrackFadeInfo? = this.outgoing,
        incoming: TrackFadeInfo? = this.incoming,
    ) = CrossfadePolicy.overlapFor(enabled, hasNext, repeatMode, durationMs, outgoing, incoming)

    @Test
    fun fadesBetweenAlbumsForTheOutgoingTracksOverlap() = assertEquals(4_000L, overlap())

    @Test
    fun repeatAllStillFades() = assertEquals(4_000L, overlap(repeatMode = Player.REPEAT_MODE_ALL))

    @Test
    fun theSettingOffNeverFades() = assertNull(overlap(enabled = false))

    @Test
    fun theLastTrackDoesNotFade() = assertNull(overlap(hasNext = false))

    @Test
    fun repeatOneDoesNotFade() = assertNull(overlap(repeatMode = Player.REPEAT_MODE_ONE))

    @Test
    fun theSameAlbumStaysGapless() = assertNull(overlap(incoming = TrackFadeInfo(3_000L, "album-a")))

    @Test
    fun aTrackWithoutARampDoesNotFade() = assertNull(overlap(outgoing = TrackFadeInfo(null, "album-a")))

    @Test
    fun unknownInfoDoesNotFade() {
        assertNull(overlap(outgoing = null))
        assertNull(overlap(incoming = null))
    }

    @Test
    fun anUnknownAlbumOnEitherSideDoesNotFade() {
        assertNull(overlap(outgoing = TrackFadeInfo(4_000L, null)))
        assertNull(overlap(incoming = TrackFadeInfo(3_000L, null)))
    }

    @Test
    fun aTrackTooShortToArmAndFadeDoesNotFade() {
        assertNull(overlap(durationMs = C.TIME_UNSET))
        assertNull(overlap(durationMs = 2 * 4_000L + CrossfadePolicy.LEAD_MS))
        assertEquals(4_000L, overlap(durationMs = 2 * 4_000L + CrossfadePolicy.LEAD_MS + 1))
    }
}
