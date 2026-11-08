package com.cappielloantonio.tempo.ui.fragment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * docs/web/settings-help.md as the bundleSettingsHelp task in app/build.gradle
 * ships it, checked against the rail: a category without help, or a section
 * whose id names no category, fails here rather than in the car.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsHelpAssetTest {
    private val bundled =
        RuntimeEnvironment
            .getApplication()
            .assets
            .open(SettingsHelpFragment.ASSET)
            .bufferedReader()
            .use { it.readText() }

    @Test
    fun `every category but About has exactly one section, and nothing else does`() {
        assertEquals(
            SettingsCategory.entries.mapNotNull { it.helpId }.sorted(),
            CarMarkdown.sectionIds(bundled).sorted(),
        )
    }

    /** section() ends a section at every `## `, so an unanchored one would cut another short. */
    @Test
    fun `every second-level heading carries an id`() {
        bundled.lines().filter { it.startsWith("## ") }.forEach { line ->
            assertTrue("no {#id} on: $line", CarMarkdown.sectionIds(line).size == 1)
        }
    }

    @Test
    fun `every section explains at least one setting`() {
        SettingsCategory.entries.mapNotNull { it.helpId }.forEach { id ->
            val blocks = CarMarkdown.parse(CarMarkdown.section(bundled, id)!!)
            assertTrue("$id has no ### setting", blocks.any { it is MarkdownBlock.Heading && it.level == 3 })
        }
    }
}
