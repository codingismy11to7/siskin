package com.cappielloantonio.tempo.util

import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.service.MediaBrowserTree

/**
 * Robolectric keeps App's SharedPreferences in a static field between test
 * methods, and across test classes in the same JVM fork. Any class that reads
 * or writes the tab order must therefore reset it rather than assume absence,
 * or an earlier method -- possibly in another class -- decides its result.
 *
 * Shared rather than repeated: five classes need this, and a key spelled
 * differently in one of them would be a silently flaky test.
 */
object BrowseTabOrderFixture {
    const val KEY = Preferences.BROWSE_TAB_ORDER

    fun clearSavedOrder() {
        App
            .getInstance()
            .preferences
            .edit()
            .remove(KEY)
            .commit()
    }

    @UnstableApi
    fun moreChildren(): List<String> =
        MediaBrowserTree
            .getChildren(Constants.MORE_ID)
            .get()
            .value!!
            .map { it.mediaId }
}
