package com.cappielloantonio.tempo.plex

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.cappielloantonio.tempo.plex.api.auth.AuthClient
import com.cappielloantonio.tempo.plex.api.auth.SwitchUserError
import com.cappielloantonio.tempo.plex.auth.PendingAccount
import com.cappielloantonio.tempo.plex.auth.SignInError
import com.cappielloantonio.tempo.plex.base.PlexResponse
import com.cappielloantonio.tempo.plex.models.HomeUser
import com.cappielloantonio.tempo.plex.models.PlexAccount
import com.cappielloantonio.tempo.plex.models.Resource
import com.cappielloantonio.tempo.util.PlexResourceFixture.aMediaServer
import com.google.gson.Gson
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify

class HomeUserSwitchTest {
    private val jill =
        HomeUser().apply {
            uuid = "jill-uuid"
            title = "jill"
            restricted = true
        }
    private val switched =
        PlexAccount().apply {
            authToken = "jill-token"
            uuid = "jill-uuid"
            title = "jill"
        }

    /** Deliberately not the saved session's token: the switch is made from whatever it is given. */
    private val from = PendingAccount("pending-token")

    private val saved =
        PlexSession("admin-token", "https://saved.example", SectionKey("7"), null, "machine-id", owned = true)

    private fun shared(token: String? = "jill-server-token") =
        aMediaServer(accessToken = token, clientIdentifier = "machine-id").apply { owned = false }

    private fun sections(vararg keys: String): PlexResponse =
        Gson().fromJson(
            """{"MediaContainer":{"Directory":[${keys.joinToString(",") { """{"key":"$it","type":"artist","title":"M$it"}""" }}]}}""",
            PlexResponse::class.java,
        )

    private fun switcher(
        switch: Either<SwitchUserError, PlexAccount> = switched.right(),
        resources: List<Resource> = listOf(shared()),
        sectionsAnswer: Either<PlexTransportFailure, PlexResponse> = sections("7").right(),
        seenTokens: MutableList<String?> = mutableListOf(),
    ): Pair<HomeUserSwitch, AuthClient> {
        val current = mock<AuthClient>().stub { on { switchTo(any(), anyOrNull()) } doReturn switch }
        val asNew = mock<AuthClient>().stub { on { getResources() } doReturn resources.right() }
        val switcher =
            HomeUserSwitch(
                authClientFor = { token ->
                    when (token) {
                        "pending-token" -> current
                        "jill-token" -> asNew
                        else -> error("plex.tv called as $token")
                    }
                },
                sectionsFor = { _, serverToken, accountToken ->
                    seenTokens += serverToken ?: accountToken
                    sectionsAnswer
                },
            )
        return switcher to current
    }

    @Test
    fun keepsTheSavedServerAndLibraryWhenTheNewUserCanSeeBoth() =
        runTest {
            val tokens = mutableListOf<String?>()
            val outcome = switcher(seenTokens = tokens).first.switch(from, jill, null, saved).getOrNull()

            assertTrue("got $outcome", outcome is SwitchOutcome.Committable)
            outcome as SwitchOutcome.Committable
            assertEquals("https://saved.example", outcome.serverUri)
            assertEquals(SectionKey("7"), outcome.sectionKey)
            assertEquals(PendingAccount("jill-token"), outcome.account)
            // The library check runs as the new user's *server* token, never
            // their plex.tv one, which that server refuses.
            assertEquals(listOf<String?>("jill-server-token"), tokens)
        }

    @Test
    fun anOwnedServerWithNoTokenIsAskedAsTheNewUser() =
        runTest {
            // The fallback for a null server token is the new member's token,
            // never the stored one, which is still the previous member's.
            val ownedUnknown = aMediaServer(accessToken = null, clientIdentifier = "machine-id")
            val ownedTrue = aMediaServer(accessToken = null, clientIdentifier = "machine-id").apply { owned = true }
            for (resource in listOf(ownedUnknown, ownedTrue)) {
                val tokens = mutableListOf<String?>()
                val outcome = switcher(resources = listOf(resource), seenTokens = tokens).first.switch(from, jill, null, saved).getOrNull()

                assertTrue("got $outcome", outcome is SwitchOutcome.Committable)
                assertEquals(listOf<String?>("jill-token"), tokens)
            }
        }

    @Test
    fun asksForALibraryWhenTheSavedOneIsHiddenFromTheNewUser() =
        runTest {
            val outcome = switcher(sectionsAnswer = sections("3", "4").right()).first.switch(from, jill, null, saved).getOrNull()

            assertTrue("got $outcome", outcome is SwitchOutcome.NeedsLibrary)
            assertEquals(listOf("3", "4"), (outcome as SwitchOutcome.NeedsLibrary).sections.map { it.key })
        }

    @Test
    fun asksForAServerWhenTheSavedOneIsNotTheNewUsers() =
        runTest {
            val other = aMediaServer(accessToken = "x", clientIdentifier = "other-machine")
            val outcome = switcher(resources = listOf(other)).first.switch(from, jill, null, saved).getOrNull()

            assertTrue("got $outcome", outcome is SwitchOutcome.NeedsServer)
        }

    @Test
    fun asksForAServerWhenThereIsNoSavedSession() =
        runTest {
            // Sign-in: nothing is saved yet, so there is nothing to keep.
            val outcome = switcher().first.switch(from, jill, null, null).getOrNull()

            assertTrue("got $outcome", outcome is SwitchOutcome.NeedsServer)
        }

    @Test
    fun anUnownedSavedServerWithNoTokenFallsBackToTheServerPicker() =
        runTest {
            val outcome = switcher(resources = listOf(shared(token = null))).first.switch(from, jill, null, saved).getOrNull()

            assertTrue("got $outcome", outcome is SwitchOutcome.NeedsServer)
            assertEquals(PendingAccount("jill-token"), outcome!!.account)
        }

    @Test
    fun anUnreachableSavedServerFallsBackToTheServerPicker() =
        runTest {
            // Typically the home LAN address, from the road.
            val outcome =
                switcher(sectionsAnswer = PlexTransportFailure.Unreachable(PlexHost.Server).left())
                    .first
                    .switch(from, jill, null, saved)
                    .getOrNull()

            assertTrue("got $outcome", outcome is SwitchOutcome.NeedsServer)
            assertEquals(PendingAccount("jill-token"), outcome!!.account)
        }

    @Test
    fun anHttpErrorFromTheSavedServerFallsBackToTheServerPicker() =
        runTest {
            val outcome =
                switcher(sectionsAnswer = PlexTransportFailure.Http(PlexHost.Server, 401).left())
                    .first
                    .switch(from, jill, null, saved)
                    .getOrNull()

            assertTrue("got $outcome", outcome is SwitchOutcome.NeedsServer)
        }

    @Test
    fun aSavedServerWithNoMusicFallsBackToTheServerPicker() =
        runTest {
            val outcome = switcher(sectionsAnswer = sections().right()).first.switch(from, jill, null, saved).getOrNull()

            assertTrue("got $outcome", outcome is SwitchOutcome.NeedsServer)
        }

    @Test
    fun aResourcesFailureStillFailsTheSwitch() =
        runTest {
            val failure = PlexTransportFailure.Unreachable(PlexHost.PlexTv)
            val current = mock<AuthClient>().stub { on { switchTo(any(), anyOrNull()) } doReturn switched.right() }
            val asNew = mock<AuthClient>().stub { on { getResources() } doReturn failure.left() }
            val result =
                HomeUserSwitch(
                    { token -> if (token == "pending-token") current else asNew },
                    { _, _, _ -> error("not reached") },
                ).switch(from, jill, null, saved)

            assertEquals(SignInError.Api(failure).left(), result)
        }

    @Test
    fun switchesFromTheTokenItIsGiven() =
        runTest {
            val (switcher, current) = switcher()
            switcher.switch(from, jill, "4321", saved)

            // authClientFor rejects every other token, so reaching this means
            // the switch was made as `from`, not as the saved session.
            verify(current).switchTo("jill-uuid", "4321")
        }

    @Test
    fun mapsSwitchRefusalsToSignInErrors() =
        runTest {
            assertEquals(
                SignInError.WrongPin.left(),
                switcher(switch = SwitchUserError.WrongPin.left()).first.switch(from, jill, "0000", saved),
            )
            assertEquals(
                SignInError.RateLimited.left(),
                switcher(switch = SwitchUserError.RateLimited.left()).first.switch(from, jill, "0000", saved),
            )
            val failure = PlexTransportFailure.Unreachable(PlexHost.PlexTv)
            assertEquals(
                SignInError.Api(failure).left(),
                switcher(switch = SwitchUserError.Transport(failure).left()).first.switch(from, jill, "0000", saved),
            )
        }

    @Test
    fun aRefusedSwitchListsNoServers() =
        runTest {
            val asNew = mock<AuthClient>()
            val current = mock<AuthClient>().stub { on { switchTo(any(), anyOrNull()) } doReturn SwitchUserError.WrongPin.left() }
            HomeUserSwitch(
                { token -> if (token == "pending-token") current else asNew },
                { _, _, _ -> error("not reached") },
            ).switch(from, jill, "0000", saved)

            verify(asNew, never()).getResources()
        }
}
