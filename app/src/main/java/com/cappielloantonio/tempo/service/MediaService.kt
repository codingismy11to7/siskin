package com.cappielloantonio.tempo.service

import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.repository.PlexBrowseRepository
import com.cappielloantonio.tempo.repository.SessionMediaItemRepository

@UnstableApi
class MediaService : BaseMediaService() {
    private val browseRepository = PlexBrowseRepository()
    private val sessionMediaItemRepository = SessionMediaItemRepository()

    override fun getMediaLibrarySessionCallback(): MediaLibrarySession.Callback {
        if (sessionCallback == null) {
            sessionCallback = MediaLibrarySessionCallback(
                baseContext,
                this,
                browseRepository,
                sessionMediaItemRepository
            )
        }
        return sessionCallback!!
    }

    override fun releasePlayers() {
        sessionMediaItemRepository.deleteAll()
        super.releasePlayers()
    }

    // This service is what owns the repository, so its teardown is the only
    // point at which the repository's browse scope is known to be finished with.
    override fun onDestroy() {
        browseRepository.release()
        super.onDestroy()
    }
}
