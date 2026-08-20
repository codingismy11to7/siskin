package com.cappielloantonio.tempo.util

import android.content.Context
import androidx.annotation.VisibleForTesting
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
    private val context: Context,
) : MediaSource.Factory {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val dataSourceFactory = buildDataSourceFactory()

        return when {
            mediaItem.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8 ||
                mediaItem.localConfiguration
                    ?.uri
                    ?.lastPathSegment
                    ?.endsWith(".m3u8", ignoreCase = true) == true -> {
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            }

            else -> {
                val extractorsFactory: ExtractorsFactory = DefaultExtractorsFactory()
                val progressiveFactory = ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)

                progressiveFactory.createMediaSource(mediaItem)
            }
        }
    }

    /**
     * The DataSource.Factory every stream is built on: whichever of
     * DownloadUtil's two factories the streaming-cache-size preference
     * selects, wrapped in a ServerAddressResolver so the live server address
     * lands on the request even when the queue was built against an address
     * that has since changed.
     *
     * Wrapped here rather than inside DownloadUtil.getCacheDataSourceFactory,
     * which already has a ResolvingDataSource of its own: that one is only on
     * the caching path, so a resolver added there would silently not run for
     * anyone who set the streaming cache to zero.
     *
     * Factored out of createMediaSource and exposed as @VisibleForTesting so
     * DynamicMediaSourceFactoryTest can open a real DataSpec through exactly
     * the factory createMediaSource hands to media3, rather than reflecting
     * into ProgressiveMediaSource's private field to recover what was passed
     * in -- the same escape-hatch pattern ServerAddressBook.newForTest and
     * .resetForTest already establish, not a new one. This is production
     * code, unconditionally called from createMediaSource; the annotation
     * only widens who may call it, it does not change what it does.
     */
    @VisibleForTesting
    fun buildDataSourceFactory(): DataSource.Factory {
        val streamingCacheSize = Preferences.getStreamingCacheSize()
        val useUpstream = streamingCacheSize <= 0L

        val selected: DataSource.Factory =
            if (useUpstream) {
                DownloadUtil.getUpstreamDataSourceFactory(context)
            } else {
                DownloadUtil.getCacheDataSourceFactory(context)
            }

        return ResolvingDataSource.Factory(selected, ServerAddressResolver(ServerAddressBook.shared))
    }

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
        TODO("Not yet implemented")
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
        TODO("Not yet implemented")
    }

    override fun getSupportedTypes(): IntArray =
        intArrayOf(
            C.CONTENT_TYPE_HLS,
            C.CONTENT_TYPE_OTHER,
        )
}
