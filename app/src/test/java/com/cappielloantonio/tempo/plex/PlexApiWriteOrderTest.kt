package com.cappielloantonio.tempo.plex

import android.content.SharedPreferences
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.plex.account.PlexAccountStore
import org.junit.After
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
 * A Home member's account token must never be readable beside no server token
 * of theirs on a server they do not own: the fallback would send their plex.tv
 * token to the server, which answers 401 and makes the car offer "sign in
 * again".
 *
 * Robolectric's account manager keeps auth tokens across a password change and
 * a device's does not, so the store here is a fake that models the device:
 * changing the account token deletes every server token. After every account
 * write and every preferences change, the session as a reader would see it is
 * checked against what each server accepts.
 */
@RunWith(RobolectricTestRunner::class)
class PlexApiWriteOrderTest {
    private val events = mutableListOf<String>()
    private val violations = mutableListOf<String>()
    private var observing = false

    private var password: String? = null
    private val serverTokens = mutableMapOf<String?, String>()

    private val store: PlexAccountStore =
        mock<PlexAccountStore>().also { store ->
            doAnswer { password != null }.whenever(store).hasAccount()
            doAnswer { password }.whenever(store).accountToken()
            doAnswer { if (password == null) null else serverTokens[it.arguments[0] as String?] }
                .whenever(store)
                .serverToken(anyOrNull())
            doAnswer {
                val value = it.arguments[0] as String?
                if (value == null || (password != null && password != value)) serverTokens.clear()
                password = value
                record("account($value)")
            }.whenever(store).setAccountToken(anyOrNull())
            doAnswer {
                val machine = it.arguments[0] as String?
                val value = it.arguments[1] as String?
                if (password != null) {
                    if (value == null) serverTokens.remove(machine) else serverTokens[machine] = value
                }
                record("server($machine, $value)")
            }.whenever(store).setServerToken(anyOrNull(), anyOrNull())
        }

    private val api = PlexApi { store }

    private val preferences: SharedPreferences get() = App.getInstance().preferences

    // Held in a field: SharedPreferences keeps its listeners weakly.
    private val onPreferenceChange = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> check("prefs") }

    @Before
    fun startSignedOut() {
        // Robolectric keeps SharedPreferences across methods.
        api.session = null
        preferences.registerOnSharedPreferenceChangeListener(onPreferenceChange)
    }

    @After
    fun stopListening() {
        preferences.unregisterOnSharedPreferenceChangeListener(onPreferenceChange)
    }

    private fun record(event: String) {
        if (!observing) return
        val owned = if (api.serverOwned) "owned" else "not owned"
        events += "$event with prefs on ${api.machineIdentifier}, $owned"
        check(event)
    }

    /** What a reader would get now: no session, or one its server accepts. */
    private fun check(after: String) {
        if (!observing) return
        val session = api.session ?: return
        val machine = session.serverUri.removePrefix("https://").removeSuffix(".example")
        val sent = session.serverToken ?: session.accountToken
        if (machine != session.machineIdentifier || sent !in ACCEPTED.getValue(machine)) {
            violations += "after $after: $session"
        }
    }

    private fun transition(
        from: PlexSession,
        to: PlexSession,
    ) {
        api.session = from
        assertEquals(from, api.session)
        observing = true

        api.session = to

        observing = false
        assertEquals(emptyList<String>(), violations)
        assertEquals(to, api.session)
    }

    @Test
    fun ownerToMemberOnTheSameServer() = transition(OWNER_A, MEMBER_A)

    @Test
    fun memberToOwnerOnTheSameServer() = transition(MEMBER_A, OWNER_A)

    @Test
    fun ownerToMemberAcrossServers() = transition(OWNER_A, MEMBER_B)

    @Test
    fun memberToOwnerAcrossServers() = transition(MEMBER_B, OWNER_A)

    @Test
    fun memberToMemberOnTheSameServer() = transition(MEMBER_A, OTHER_MEMBER_A)

    @Test
    fun memberToMemberAcrossServers() = transition(MEMBER_A, OTHER_MEMBER_B)

    @Test
    fun anOwnerToAnotherAccountThatOwnsItsServer() = transition(BOB_B, OWNER_A)

    @Test
    fun aMemberChangingLibrary() = transition(MEMBER_A, MEMBER_A.copy(musicSectionKey = SectionKey("5")))

    @Test
    fun anOwnerChangingLibrary() = transition(OWNER_A, OWNER_A.copy(musicSectionKey = SectionKey("5")))

    @Test
    fun theFirstSignInAsAnOwner() {
        observing = true
        api.session = OWNER_A
        observing = false

        assertEquals(emptyList<String>(), violations)
        assertEquals(OWNER_A, api.session)
    }

    @Test
    fun theFirstSignInAsAMember() {
        observing = true
        api.session = MEMBER_A
        observing = false

        assertEquals(emptyList<String>(), violations)
        assertEquals(MEMBER_A, api.session)
    }

    @Test
    fun aChangedAccountTokenIsWrittenOnlyOnceTheOldSessionReadsAsNotOwned() {
        api.session = OWNER_A
        observing = true

        api.session = MEMBER_B

        assertEquals(
            listOf(
                "account(member) with prefs on machine-a, not owned",
                "server(machine-b, member@b) with prefs on machine-b, not owned",
                "server(machine-a, null) with prefs on machine-b, not owned",
            ),
            events,
        )
    }

    @Test
    fun anUnchangedAccountTokenFollowsThePreferences() {
        api.session = MEMBER_A
        observing = true

        api.session = MEMBER_B.copy(accountToken = "member")

        assertEquals(
            listOf(
                "account(member) with prefs on machine-b, not owned",
                "server(machine-b, member@b) with prefs on machine-b, not owned",
                "server(machine-a, null) with prefs on machine-b, not owned",
            ),
            events,
        )
    }

    @Test
    fun theSameServerClearsNothingExtra() {
        api.session = MEMBER_A
        observing = true

        api.session = MEMBER_A.copy(musicSectionKey = SectionKey("5"))

        assertEquals(
            listOf(
                "account(member) with prefs on machine-a, not owned",
                "server(machine-a, member@a) with prefs on machine-a, not owned",
            ),
            events,
        )
    }

    @Test
    fun clearingTheSessionDropsTheServerTokenAfterThePreferences() {
        api.session = MEMBER_A
        observing = true

        api.session = null

        assertEquals(listOf("server(machine-a, null) with prefs on null, owned"), events)
        assertEquals("member", password)
    }

    private companion object {
        val OWNER_A = PlexSession("owner", "https://machine-a.example", SectionKey("4"), null, "machine-a", owned = true)
        val MEMBER_A = PlexSession("member", "https://machine-a.example", SectionKey("4"), "member@a", "machine-a", owned = false)
        val MEMBER_B = PlexSession("member", "https://machine-b.example", SectionKey("4"), "member@b", "machine-b", owned = false)
        val OTHER_MEMBER_A = PlexSession("other", "https://machine-a.example", SectionKey("4"), "other@a", "machine-a", owned = false)
        val OTHER_MEMBER_B = PlexSession("other", "https://machine-b.example", SectionKey("4"), "other@b", "machine-b", owned = false)

        /** Bob is in the owner's Home and owns machine-b himself. */
        val BOB_B = PlexSession("bob", "https://machine-b.example", SectionKey("4"), null, "machine-b", owned = true)

        /** Each server accepts its owner's account token and its members' server tokens, nothing else. */
        val ACCEPTED =
            mapOf(
                "machine-a" to setOf("owner", "member@a", "other@a"),
                "machine-b" to setOf("bob", "member@b", "other@b"),
            )
    }
}
