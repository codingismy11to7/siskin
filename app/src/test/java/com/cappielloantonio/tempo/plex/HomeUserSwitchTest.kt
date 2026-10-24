package com.cappielloantonio.tempo.plex

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.cappielloantonio.tempo.plex.api.auth.AuthClient
import com.cappielloantonio.tempo.plex.api.auth.SwitchUserError
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

    private val saved =
        PlexSession("admin-token", "https://saved.example", SectionKey("7"), null, "machine-id")

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
        sectionKeys: List<String> = listOf("7"),
        seenTokens: MutableList<String?> = mutableListOf(),
    ): Pair<HomeUserSwitch, AuthClient> {
        val current = mock<AuthClient>().stub { on { switchTo(any(), anyOrNull()) } doReturn switch }
        val asNew = mock<AuthClient>().stub { on { getResources() } doReturn resources.right() }
        val switcher =
            HomeUserSwitch(
                authClient = current,
                authClientFor = { token ->
                    assertEquals("jill-token", token)
                    asNew
                },
                sectionsFor = { _, token ->
                    seenTokens += token
                    sections(*sectionKeys.toTypedArray()).right()
                },
            )
        return switcher to current
    }

    @Test
    fun keepsTheSavedServerAndLibraryWhenTheNewUserCanSeeBoth() =
        runTest {
            val tokens = mutableListOf<String?>()
            val outcome = switcher(seenTokens = tokens).first.switch(jill, null, saved).getOrNull()

            assertTrue("got $outcome", outcome is SwitchOutcome.Committable)
            outcome as SwitchOutcome.Committable
            assertEquals("https://saved.example", outcome.serverUri)
            assertEquals(SectionKey("7"), outcome.sectionKey)
            // The library check runs as the new user's *server* token, never
            // their plex.tv one, which that server refuses.
            assertEquals(listOf<String?>("jill-server-token"), tokens)
        }

    @Test
    fun asksForALibraryWhenTheSavedOneIsHiddenFromTheNewUser() =
        runTest {
            val outcome = switcher(sectionKeys = listOf("3", "4")).first.switch(jill, null, saved).getOrNull()

            assertTrue("got $outcome", outcome is SwitchOutcome.NeedsLibrary)
            assertEquals(listOf("3", "4"), (outcome as SwitchOutcome.NeedsLibrary).sections.map { it.key })
        }

    @Test
    fun asksForAServerWhenTheSavedOneIsNotTheNewUsers() =
        runTest {
            val other = aMediaServer(accessToken = "x", clientIdentifier = "other-machine")
            val outcome = switcher(resources = listOf(other)).first.switch(jill, null, saved).getOrNull()

            assertTrue("got $outcome", outcome is SwitchOutcome.NeedsServer)
        }

    @Test
    fun asksForAServerWhenThereIsNoSavedSession() =
        runTest {
            // Sign-in: nothing is saved yet, so there is nothing to keep.
            val outcome = switcher().first.switch(jill, null, null).getOrNull()

            assertTrue("got $outcome", outcome is SwitchOutcome.NeedsServer)
        }

    @Test
    fun refusesAnUnownedServerThatCameWithNoToken() =
        runTest {
            val result = switcher(resources = listOf(shared(token = null))).first.switch(jill, null, saved)

            assertEquals(SignInError.ServerTokenMissing.left(), result)
        }

    @Test
    fun sendsThePinItWasGiven() =
        runTest {
            val (switcher, current) = switcher()
            switcher.switch(jill, "4321", saved)

            verify(current).switchTo("jill-uuid", "4321")
        }

    @Test
    fun mapsSwitchRefusalsToSignInErrors() =
        runTest {
            assertEquals(SignInError.WrongPin.left(), switcher(switch = SwitchUserError.WrongPin.left()).first.switch(jill, "0000", saved))
            assertEquals(
                SignInError.RateLimited.left(),
                switcher(switch = SwitchUserError.RateLimited.left()).first.switch(jill, "0000", saved),
            )
        }

    @Test
    fun aRefusedSwitchListsNoServers() =
        runTest {
            val asNew = mock<AuthClient>()
            val current = mock<AuthClient>().stub { on { switchTo(any(), anyOrNull()) } doReturn SwitchUserError.WrongPin.left() }
            HomeUserSwitch(current, { asNew }, { _, _ -> error("not reached") }).switch(jill, "0000", saved)

            verify(asNew, never()).getResources()
        }
}
