package com.cappielloantonio.tempo.service

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.util.ConstantsAA
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The picker's self-returning message row.
 *
 * Robolectric rather than plain JUnit because the title comes from a real string
 * resource. The row goes nowhere near `treeNodes`, so no `buildTree()` is needed.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class MediaBrowserTreePickerTest {

    @Test
    fun tappingAMessageRowReturnsTheRow() {
        val message = App.getInstance().getString(R.string.aa_library_picker_offline)
        val id = ConstantsAA.PICK_MESSAGE_ID + message

        val result = MediaBrowserTree.getChildren(id).get()

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        val row = requireNotNull(result.value).single()
        assertEquals(id, row.mediaId)
        assertEquals(message, row.mediaMetadata.title?.toString())
        assertEquals(true, row.mediaMetadata.isBrowsable)
        assertEquals(false, row.mediaMetadata.isPlayable)
    }
}
