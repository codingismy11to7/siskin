package com.cappielloantonio.tempo.service.crossfade

import com.cappielloantonio.tempo.plex.models.Media
import com.cappielloantonio.tempo.plex.models.Metadata
import com.cappielloantonio.tempo.plex.models.Part
import com.cappielloantonio.tempo.plex.models.Stream
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackFadeInfoTest {
    private fun track(
        album: String?,
        vararg streams: Stream,
    ) = Metadata().apply {
        parentRatingKey = album
        media = listOf(Media().apply { part = listOf(Part().apply { stream = streams.toList() }) })
    }

    private fun stream(
        type: Int,
        endRamp: String?,
    ) = Stream().apply {
        streamType = type
        this.endRamp = endRamp
    }

    @Test
    fun readsTheAudioStreamsEndRampAndTheAlbum() {
        val info = TrackFadeInfo.from(track("900", stream(2, "-5.07 3.31;-2.38 3.61;")))
        assertEquals(TrackFadeInfo(3_540L, "900"), info)
    }

    @Test
    fun ignoresStreamsThatAreNotAudio() {
        assertEquals(TrackFadeInfo(null, "900"), TrackFadeInfo.from(track("900", stream(4, "-5.07 3.31;-2.38 3.61;"))))
    }

    @Test
    fun noMetadataIsNoFadeAndNoAlbum() {
        assertEquals(TrackFadeInfo(null, null), TrackFadeInfo.from(null))
    }
}
