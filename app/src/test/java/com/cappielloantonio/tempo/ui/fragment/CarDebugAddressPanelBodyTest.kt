package com.cappielloantonio.tempo.ui.fragment

import com.cappielloantonio.tempo.plex.api.server.ServerAddressBook.KnownAddresses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CarDebugFragment.buildAddressPanelBody` is the pure half of the debug
 * panel behind the version line -- see the 2026-08-14 design. It touches no
 * Android framework class, which is what lets this suite run as a plain JUnit
 * test with neither Robolectric nor a fragment: `unitTests.returnDefaultValues
 * = true` stubs `android.jar`, so a test that only exercised framework classes
 * could pass while asserting nothing, and keeping the function framework-free
 * is what keeps that failure mode out of reach here.
 */
class CarDebugAddressPanelBodyTest {
    // Arbitrary, distinct from the real string resources on purpose -- these
    // tests are about which label lands where, not about the app's copy.
    private val none = "NONE"
    private val inUse = "IN_USE"
    private val direct = "DIRECT"
    private val relay = "RELAY"

    private fun body(
        known: KnownAddresses,
        outcome: String? = null,
    ) = CarDebugFragment.buildAddressPanelBody(
        known = known,
        outcome = outcome,
        noneLabel = none,
        inUseLabel = inUse,
        directLabel = direct,
        relayLabel = relay,
    )

    @Test
    fun `the in-use marker lands on the current address and no other`() {
        val text =
            body(
                KnownAddresses(
                    current = "https://b.example",
                    direct = listOf("https://a.example", "https://b.example"),
                    relay = emptyList(),
                ),
            )

        val markedLines = text.lines().filter { it.contains(inUse) }
        assertEquals(1, markedLines.size)
        assertTrue(markedLines.single().startsWith("https://b.example"))
    }

    @Test
    fun `a current address absent from every candidate appears exactly once`() {
        val text =
            body(
                KnownAddresses(
                    current = "https://elsewhere.example",
                    direct = listOf("https://a.example"),
                    relay = listOf("https://r.example"),
                ),
            )

        val matchingLines = text.lines().filter { it.contains("https://elsewhere.example") }
        assertEquals(1, matchingLines.size)
        assertTrue(matchingLines.single().contains(inUse))
    }

    @Test
    fun `an empty candidate list with a live current does not claim nothing is stored`() {
        val text =
            body(
                KnownAddresses(current = "https://only.example", direct = emptyList(), relay = emptyList()),
            )

        // This is Finding 4, pinned: the pre-address-book session has no
        // stored candidates at all while still being live, so the empty-list
        // message must not print above the address actually in use.
        assertFalse(text.contains(none))
        assertTrue(text.contains("https://only.example"))
        assertTrue(text.contains(inUse))
    }

    @Test
    fun `no addresses at all and no current prints the empty message`() {
        val text = body(KnownAddresses(current = null, direct = emptyList(), relay = emptyList()))

        assertTrue(text.contains(none))
    }

    @Test
    fun `direct and relay entries remain distinguishable`() {
        val text =
            body(
                KnownAddresses(
                    current = "https://a.example",
                    direct = listOf("https://a.example"),
                    relay = listOf("https://relay.example.plex.direct:12345"),
                ),
            )

        // Finding 3, pinned: a relay URI and a direct-but-remote one are both
        // *.plex.direct-shaped and differ only by port, so the panel has to
        // keep the two tiers apart rather than concatenating them -- this
        // asserts each candidate falls under its own group label, in order,
        // rather than being flattened into one undifferentiated list.
        val lines = text.lines()
        val directLabelIndex = lines.indexOfFirst { it == direct }
        val directAddressIndex = lines.indexOfFirst { it == "https://a.example  <- $inUse" }
        val relayLabelIndex = lines.indexOfFirst { it == relay }
        val relayAddressIndex = lines.indexOfFirst { it == "https://relay.example.plex.direct:12345" }

        assertTrue(directLabelIndex in 0 until directAddressIndex)
        assertTrue(relayLabelIndex in directAddressIndex until relayAddressIndex)
    }

    @Test
    fun `an outcome is prefixed as its own paragraph ahead of the addresses`() {
        val text =
            body(
                known =
                    KnownAddresses(
                        current = "https://a.example",
                        direct = listOf("https://a.example"),
                        relay = emptyList(),
                    ),
                outcome = "Moved to https://a.example",
            )

        assertTrue(text.startsWith("Moved to https://a.example\n\n"))
    }
}
