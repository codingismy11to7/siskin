package com.cappielloantonio.tempo.plex

import com.cappielloantonio.tempo.plex.account.PlexAccountStore
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * A Home member's account token must never be readable beside a server token
 * that is not theirs: the fallback would send their plex.tv token to the
 * server, which answers 401 and makes the car offer "sign in again". Writing
 * the server token first means the in-between state pairs the *new* server
 * token with the *old* account token, which the server accepts.
 */
@RunWith(RobolectricTestRunner::class)
class PlexApiWriteOrderTest {
    @Test
    fun writesTheServerTokenBeforeTheAccountTokenWhenAnAccountExists() {
        val store = mock<PlexAccountStore>()
        whenever(store.hasAccount()).thenReturn(true)
        val api = PlexApi { store }

        api.session = PlexSession("member", "https://one.example", SectionKey("4"), "srv", "machine-a", owned = false)

        inOrder(store) {
            verify(store).setServerToken("machine-a", "srv")
            verify(store).setAccountToken("member")
        }
    }

    @Test
    fun writesTheAccountTokenBeforeClearingTheServerTokenOfAnOwnedServer() {
        val store = mock<PlexAccountStore>()
        whenever(store.hasAccount()).thenReturn(true)
        val api = PlexApi { store }

        api.session = PlexSession("acct", "https://one.example", SectionKey("4"), null, "machine-a", owned = true)

        inOrder(store) {
            verify(store).setAccountToken("acct")
            verify(store).setServerToken("machine-a", null)
        }
    }

    @Test
    fun createsTheAccountFirstWhenThereIsNone() {
        // setServerToken is a no-op with no account to file it under, and with
        // no account there is no old token for a reader to pair it with.
        val store = mock<PlexAccountStore>()
        whenever(store.hasAccount()).thenReturn(false)
        val api = PlexApi { store }

        api.session = PlexSession("acct", "https://one.example", SectionKey("4"), "srv", "machine-a", owned = true)

        inOrder(store) {
            verify(store).setAccountToken("acct")
            verify(store).setServerToken("machine-a", "srv")
        }
    }
}
