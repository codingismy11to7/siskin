package com.cappielloantonio.tempo.plex.api.server

import android.util.Log
import com.cappielloantonio.tempo.plex.models.Connection
import com.cappielloantonio.tempo.plex.models.Resource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

private const val TAG = "ServerProbe"

/**
 * Picks the address to talk to a media server on, by trying them.
 *
 * A server advertises several connections and plex.tv's ordering says nothing
 * about which one *this* device can open. A containerised server lists its Docker
 * bridge gateway among the `local` addresses, and among genuine LAN addresses the
 * reachable one depends on which network the car is on -- neither is knowable from
 * the payload, which is why this probes instead of ranking.
 */
class ServerProbe(
    private val client: OkHttpClient = probeClient(),
    private val headers: Map<String, String> = emptyMap(),
) {
    /**
     * Built with `addUnsafeNonAscii` because `header(name, value)` rejects any
     * value outside 0x20-0x7E by throwing -- and this builder runs inside
     * `race`'s `launch { }`, so the throw would escape past `plexCall`, which
     * catches only IOException and HttpException. A car that names itself
     * `Škoda` would fail the probe rather than the connection. Plex asks for
     * UTF-8 here; see the 2026-08-27 vehicle device name design.
     */
    private val plexHeaders: Headers =
        Headers
            .Builder()
            .apply { headers.forEach { (name, value) -> addUnsafeNonAscii(name, value) } }
            .build()

    /**
     * The winning connection, or null when nothing answered.
     *
     * Direct connections race together; the relay is held back and tried only if
     * every direct one failed. Plex throttles relayed streams, so a relay that
     * answers faster than the LAN is still the wrong answer -- it is a fallback
     * tier, not a competitor.
     */
    suspend fun bestConnectionUri(resource: Resource): String? = bestOf(candidates(resource))

    /**
     * The same two-tier race as [bestConnectionUri], against candidates the
     * caller already holds.
     *
     * ServerAddressBook races a list it persisted at sign-in rather than one
     * just fetched from plex.tv -- that is what lets a re-probe work on a LAN
     * whose internet is down but whose Plex server is fine.
     */
    suspend fun bestOf(candidates: Candidates): String? = race(candidates.direct) ?: race(candidates.relay)

    /**
     * Every candidate at once; the first success wins and the rest are cancelled.
     *
     * The losers are cancelled rather than left to time out because a probe that
     * has already lost is holding a socket open against an address nobody is
     * waiting on -- and picking a different server moments later would stack
     * another set on top.
     */
    private suspend fun race(uris: List<String>): String? {
        if (uris.isEmpty()) return null

        return coroutineScope {
            val winner = CompletableDeferred<String?>()

            val probes =
                uris.map { uri ->
                    launch {
                        if (answers(uri)) {
                            Log.d(TAG, "reached $uri")
                            // Only the first call takes effect; the rest are no-ops.
                            winner.complete(uri)
                        }
                    }
                }

            // Nothing else would ever complete `winner` if every probe fails, and
            // awaiting it would hang for the life of the sign-in.
            launch {
                probes.joinAll()
                winner.complete(null)
            }

            winner.await().also { coroutineContext.cancelChildren() }
        }
    }

    /**
     * Cancellable because the losers of a race have to actually stop: OkHttp's
     * blocking execute() would ignore the coroutine being cancelled and hold the
     * socket until its own timeout.
     */
    private suspend fun answers(uri: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val request =
                Request
                    .Builder()
                    .url("${uri.trimEnd('/')}/identity")
                    .headers(plexHeaders)
                    .build()

            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }

            call.enqueue(
                object : Callback {
                    override fun onResponse(
                        call: okhttp3.Call,
                        response: Response,
                    ) {
                        response.use { if (cont.isActive) cont.resume(it.isSuccessful) }
                    }

                    override fun onFailure(
                        call: okhttp3.Call,
                        e: IOException,
                    ) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
            )
        }

    /** A server's connections, split into the tier that races and the fallback. */
    data class Candidates(
        val direct: List<String>,
        val relay: List<String>,
    )

    companion object {
        /**
         * Short by design. These run concurrently against addresses most of which
         * are expected to fail, so the wait is bounded by the slowest failure --
         * the 20s the server client uses would reproduce the hang this replaces.
         */
        private const val PROBE_TIMEOUT_SECONDS = 3L

        @JvmStatic
        fun probeClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(PROBE_TIMEOUT_SECONDS * 2, TimeUnit.SECONDS)
                .build()

        /**
         * The app permits no cleartext traffic, so an `http://` address cannot be
         * opened at all: the platform rejects it locally, before any socket, and
         * OkHttp surfaces that as an `UnknownServiceException`.
         *
         * This costs less than it sounds. Plex issues real certificates for
         * `*.plex.direct`, so a LAN server still advertises an https address for
         * its private IP. What it excludes is a server with secure connections
         * disabled, or one reached through a custom http access URL.
         */
        private fun Connection.isSecure(): Boolean = uri?.startsWith("https://", ignoreCase = true) == true

        /**
         * Plex's ordering is preserved within a tier. It carries no reachability
         * information, but the race does not need it to -- it only decides which
         * request is issued a few microseconds sooner.
         *
         * Cleartext is deliberately *not* filtered here, unlike in
         * [hasUsableConnection]. A rejected address fails instantly and without
         * touching the network, so dropping it early would buy nothing, and having
         * the probe re-state a policy the platform already enforces gives two places
         * to change when only one of them is load-bearing.
         */
        @JvmStatic
        fun candidates(resource: Resource): Candidates {
            val usable =
                resource.connections
                    .orEmpty()
                    .filter { !it.uri.isNullOrBlank() }

            return Candidates(
                direct = usable.filter { it.relay != true }.mapNotNull { it.uri },
                relay = usable.filter { it.relay == true }.mapNotNull { it.uri },
            )
        }

        /**
         * Whether a server is worth offering in the picker at all. Deliberately not
         * a reachability test: answering that for every server in the account means
         * probing all of them before the list can be drawn.
         *
         * It *is* a scheme test, because that much is knowable from the payload
         * alone: a server advertising nothing but cleartext can never be reached,
         * so listing it would only defer the failure until after the user picked it.
         */
        @JvmStatic
        fun hasUsableConnection(resource: Resource): Boolean = resource.connections?.any { it.isSecure() } == true
    }
}
