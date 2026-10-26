package com.cappielloantonio.tempo.util

/** Reaches [DownloadUtil]'s package-private reset from tests outside this package. */
object StreamingCacheTestHooks {
    fun release() = DownloadUtil.releaseStreamingCacheForTest()
}
