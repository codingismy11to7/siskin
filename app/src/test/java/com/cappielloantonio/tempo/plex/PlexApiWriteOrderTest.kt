package com.cappielloantonio.tempo.plex

import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.plex.account.PlexAccountStore
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * A Home member's account token must never be readable beside a server token
 * that is not theirs: the fallback would send their plex.tv token to the
 * server, which answers 401 and makes the car offer "sign in again". Each
 * account write is recorded with the machine identifier preferences held at
 * that moment, which pins where the preferences edit lands between them.
 */
@RunWith(RobolectricTestRunner::class)
class PlexApiWriteOrderTest {
    private val events = mutableListOf<String>()

    private fun storedMachine(): String? = App.getInstance().preferences.getString("plex_machine_identifier", null)

    private fun store(hasAccount: Boolean): PlexAccountStore {
        val store = mock<PlexAccountStore>()
        whenever(store.hasAccount()).thenReturn(hasAccount)
        doAnswer {
            events += "server(${it.arguments[0]}, ${it.arguments[1]}) with prefs on ${storedMachine()}"
        }.whenever(store).setServerToken(anyOrNull(), anyOrNull())
        doAnswer {
            events += "account(${it.arguments[0]}) with prefs on ${storedMachine()}"
        }.whenever(store).setAccountToken(anyOrNull())
        return store
    }

    @Before
    fun startOnMachineA() {
        // Robolectric keeps SharedPreferences across methods.
        PlexApi { mock() }.machineIdentifier = "machine-a"
    }

    @Test
    fun aNewServerTokenIsFiledBeforePreferencesNameItsMachine() {
        val store = store(hasAccount = true)

        PlexApi { store }.session =
            PlexSession("member", "https://two.example", SectionKey("4"), "srv", "machine-b", owned = false)

        assertEquals(
            listOf(
                "server(machine-b, srv) with prefs on machine-a",
                "account(member) with prefs on machine-b",
                "server(machine-a, null) with prefs on machine-b",
            ),
            events,
        )
    }

    @Test
    fun withNoNewServerTokenTheAccountTokenGoesFirstAndTheOldServerTokenLast() {
        val store = store(hasAccount = true)

        PlexApi { store }.session =
            PlexSession("acct", "https://two.example", SectionKey("4"), null, "machine-b", owned = true)

        assertEquals(
            listOf(
                "account(acct) with prefs on machine-a",
                "server(machine-b, null) with prefs on machine-b",
                "server(machine-a, null) with prefs on machine-b",
            ),
            events,
        )
    }

    @Test
    fun theSameServerClearsNothingExtra() {
        val store = store(hasAccount = true)

        PlexApi { store }.session =
            PlexSession("member", "https://one.example", SectionKey("4"), "srv", "machine-a", owned = false)

        assertEquals(
            listOf(
                "server(machine-a, srv) with prefs on machine-a",
                "account(member) with prefs on machine-a",
            ),
            events,
        )
    }

    @Test
    fun createsTheAccountFirstWhenThereIsNone() {
        // setServerToken is a no-op with no account to file it under, and with
        // no account there is no old token for a reader to pair it with.
        PlexApi { mock() }.machineIdentifier = null
        val store = store(hasAccount = false)

        PlexApi { store }.session =
            PlexSession("acct", "https://one.example", SectionKey("4"), "srv", "machine-a", owned = true)

        assertEquals(
            listOf(
                "account(acct) with prefs on null",
                "server(machine-a, srv) with prefs on machine-a",
            ),
            events,
        )
    }

    @Test
    fun clearingTheSessionDropsTheServerTokenAfterThePreferences() {
        val store = store(hasAccount = true)

        PlexApi { store }.session = null

        assertEquals(listOf("server(machine-a, null) with prefs on null"), events)
    }
}
