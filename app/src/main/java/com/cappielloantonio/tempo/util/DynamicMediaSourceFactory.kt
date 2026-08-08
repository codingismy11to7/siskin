package com.cappielloantonio.tempo.util

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import com.cappielloantonio.tempo.plex.api.server.ServerAddressBook
import com.cappielloantonio.tempo.plex.api.server.ServerAddressResolver

@UnstableApi
class DynamicMediaSourceFactory(
    private val context: Context
) : MediaSource.Factory {

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val streamingCacheSize = Preferences.getStreamingCacheSize()
        val useUpstream = streamingCacheSize <= 0L

        val selected: DataSource.Factory = if (useUpstream) {
            DownloadUtil.getUpstreamDataSourceFactory(context)
        } else {
            DownloadUtil.getCacheDataSourceFactory(context)
        }

        // Wrapped here rather than inside getCacheDataSourceFactory, which
        // already has a ResolvingDataSource of its own: that one is only on the
        // caching path, so a resolver added there would silently not run for
        // anyone who set the streaming cache to zero.
        val dataSourceFactory: DataSource.Factory =
            ResolvingDataSource.Factory(selected, ServerAddressResolver(ServerAddressBook.shared))

        return when {
            mediaItem.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8 ||
                    mediaItem.localConfiguration?.uri?.lastPathSegment?.endsWith(".m3u8", ignoreCase = true) == true -> {
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            }

            else -> {
                val extractorsFactory: ExtractorsFactory = DefaultExtractorsFactory()
                val progressiveFactory = ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)

                progressiveFactory.createMediaSource(mediaItem)
            }
        }
    }

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
        TODO("Not yet implemented")
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
        TODO("Not yet implemented")
    }

    override fun getSupportedTypes(): IntArray {
        return intArrayOf(
            C.CONTENT_TYPE_HLS,
            C.CONTENT_TYPE_OTHER
        )
    }
}