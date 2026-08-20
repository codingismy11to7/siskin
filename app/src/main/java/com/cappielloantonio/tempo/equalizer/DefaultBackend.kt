package com.cappielloantonio.tempo.equalizer

import android.content.Context

class DefaultBackend : EqualizerBackend {
    override fun attach(
        audioSessionId: Int,
        context: Context,
    ): Boolean = false

    override fun release(
        audioSessionId: Int,
        context: Context,
    ) {}

    override fun setEnabled(enabled: Boolean) {}

    override fun getNumberOfBands(): Short = 0

    override fun getBandLevelRange(): ShortArray? = null

    override fun getCenterFreq(band: Short): Int? = null

    override fun getBandLevel(band: Short): Short? = null

    override fun setBandLevel(
        band: Short,
        level: Short,
    ) {}
}
