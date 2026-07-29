package com.cappielloantonio.tempo.service

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.repository.LibraryPickerRepository
import com.cappielloantonio.tempo.util.ConstantsAA
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The picker's two self-returning rows.
 *
 * Robolectric rather than plain JUnit because both titles come from real string
 * resources. Neither row goes near `treeNodes`, so no `buildTree()` is needed.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class MediaBrowserTreePickerTest {

    @Test
    fun tappingTheConfirmationRowReturnsTheRow() {
        val id = ConstantsAA.PICK_LIBRARY_ID + "abc123|7" +
            LibraryPickerRepository.CONFIRMED_SUFFIX

        val result = MediaBrowserTree.getChildren(id).get()

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        // An empty list here is the blank screen the confirmation row exists to
        // avoid, and committing the library a second time would be worse.
        val row = requireNotNull(result.value).single()
        assertEquals(id, row.mediaId)
        assertEquals(
            // No getLibraries has run in this process, so the name falls back --
            // which is the state a tap after a process restart arrives in.
            App.getInstance().getString(R.string.aa_now_browsing, "Library 7"),
            row.mediaMetadata.title?.toString()
        )
        assertEquals(true, row.mediaMetadata.isBrowsable)
        assertEquals(false, row.mediaMetadata.isPlayable)
    }

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
