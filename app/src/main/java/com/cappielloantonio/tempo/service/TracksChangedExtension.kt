package com.cappielloantonio.tempo.service

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import com.cappielloantonio.tempo.repository.AutomotiveRepository
import com.google.common.util.concurrent.ListenableFuture

@UnstableApi
class TracksChangedExtension(
       private val automotiveRepository: AutomotiveRepository
) : MediaServiceExtension {

    @OptIn(UnstableApi::class)
    override fun handle(
        player: Player,
        item: MediaItem,
        browserFuture: ListenableFuture<MediaBrowser>
    ): Boolean {

        if (player.mediaItemCount > 1) {
            return false
        }

        return false
    }
}
