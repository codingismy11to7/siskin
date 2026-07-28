package com.cappielloantonio.tempo.service

import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.repository.AutomotiveRepository

@UnstableApi
class MediaService : BaseMediaService() {
    private val automotiveRepository = AutomotiveRepository()

    override fun getMediaLibrarySessionCallback(): MediaLibrarySession.Callback {
        if (sessionCallback == null) {
            sessionCallback = MediaLibrarySessionCallback(baseContext, this, automotiveRepository)
        }
        return sessionCallback!!
    }

    override fun releasePlayers() {
        automotiveRepository.deleteMetadata()
        super.releasePlayers()
    }
}
