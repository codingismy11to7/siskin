package com.cappielloantonio.tempo.repository

import com.cappielloantonio.tempo.plex.PlexApi
import com.cappielloantonio.tempo.plex.api.server.ServerAddressBook
import okhttp3.mockwebserver.MockWebServer

/**
 * The MockWebServer-plus-session setup every PlexBrowseRepository suite needs.
 *
 * Shared rather than copied because the reset below is load-bearing and easy to
 * get subtly wrong: App caches SharedPreferences in a static field that
 * Robolectric does not clear between methods, so every field is reset
 * explicitly rather than assumed absent. A value left over from one test is
 * otherwise visible to the next -- and a stale machineIdentifier or
 * serverCandidates would let a later fetch reach ServerAddressBook.shared's
 * re-probe path and race real addresses instead of the mock server.
 *
 * PlexSession only exists as a complete unit, so accountToken and
 * musicSectionKey are set alongside serverUri: an incomplete session falls back
 * to the placeholder base URL exactly like a missing one.
 */
class PlexBrowseTestServer {

    lateinit var server: MockWebServer
        private set

    fun start(): MockWebServer {
        server = MockWebServer()
        server.start()
        PlexApi().apply {
            accountToken = "account-token"
            serverUri = server.url("/").toString()
            musicSectionKey = "1"
            machineIdentifier = null
            serverCandidates = null
        }
        // ServerAddressBook.shared is the real production singleton -- there is
        // exactly one, by design -- so its failure-cooldown clock is shared with
        // every other test in this run. Nothing else can reach the private field
        // that holds it, so this is the reset.
        ServerAddressBook.shared.resetForTest()
        return server
    }

    fun stop() {
        server.shutdown()
    }
}
