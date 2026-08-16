package com.cappielloantonio.tempo.util

import com.cappielloantonio.tempo.App

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

    /** Must match Preferences.BROWSE_TAB_ORDER, which is private. */
    const val KEY = "browse_tab_order"

    fun clearSavedOrder() {
        App.getInstance().preferences.edit().remove(KEY).commit()
    }
}
