package com.cappielloantonio.tempo.ui.fragment

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** What the trimChangelog task in app/build.gradle bundles. */
@RunWith(RobolectricTestRunner::class)
class ChangelogAssetTest {
    private val bundled =
        RuntimeEnvironment
            .getApplication()
            .assets
            .open(DebugChangelogFragment.ASSET)
            .bufferedReader()
            .use { it.readText() }

    @Test
    fun `it starts at Unreleased`() {
        assertTrue(bundled.startsWith("## [Unreleased] — Siskin"))
    }

    @Test
    fun `it runs through the first Siskin release`() {
        assertTrue(bundled.contains("\n## [0.99.0] "))
        assertTrue(bundled.contains("Rebranded from Tempus to Siskin"))
    }

    @Test
    fun `it leaves out the Tempus history`() {
        assertFalse(bundled.contains("\n## [4."))
        assertFalse(bundled.contains("What's Changed"))
    }
}
