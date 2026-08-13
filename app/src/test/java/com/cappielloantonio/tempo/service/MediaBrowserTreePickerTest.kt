package com.cappielloantonio.tempo.service

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.repository.LibraryPickerRepository
import com.cappielloantonio.tempo.util.Constants
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
        val id = Constants.PICK_LIBRARY_ID + "abc123|7" +
            LibraryPickerRepository.CONFIRMED_SUFFIX

        val result = MediaBrowserTree.getChildren(id).get()

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        // An empty list here is the blank screen the confirmation row exists to
        // avoid, and committing the library a second time would be worse.
        val row = requireNotNull(result.value).single()
        assertEquals(id, row.mediaId)
        assertEquals(
            // No getLibraries has run in this process, so both the library name
            // and the candidate's server name are gone -- the state a tap after a
            // process restart arrives in, and why the no-server string exists.
            App.getContext().getString(R.string.browse_now_browsing_no_server, "Library 7"),
            row.mediaMetadata.title?.toString()
        )
        assertEquals(true, row.mediaMetadata.isBrowsable)
        assertEquals(false, row.mediaMetadata.isPlayable)
    }

    @Test
    fun tappingAMessageRowReturnsTheRow() {
        val message = App.getContext().getString(R.string.browse_library_picker_offline)
        val id = Constants.PICK_MESSAGE_ID + message

        val result = MediaBrowserTree.getChildren(id).get()

        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        val row = requireNotNull(result.value).single()
        assertEquals(id, row.mediaId)
        assertEquals(message, row.mediaMetadata.title?.toString())
        assertEquals(true, row.mediaMetadata.isBrowsable)
        assertEquals(false, row.mediaMetadata.isPlayable)
    }
}
