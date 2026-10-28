package com.cappielloantonio.tempo.ui.fragment

import com.cappielloantonio.tempo.plex.api.server.ServerAddressBook
import com.cappielloantonio.tempo.plex.models.Connection
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * What an address is. [LOCAL], [REMOTE] and [RELAY] are plex.tv's own flags;
 * [DIRECT] is all the stored list can say when plex.tv was not reached.
 */
internal enum class AddressKind { LOCAL, REMOTE, RELAY, DIRECT }

internal data class AddressRow(
    val uri: String,
    val kind: AddressKind,
    val hostPort: String,
    val inUse: Boolean,
    /** plex.tv's flags for this address, or null when it did not list it. */
    val flags: Flags?,
    val dockerRange: Boolean,
) {
    data class Flags(
        val local: Boolean,
        val relay: Boolean,
    )
}

/**
 * The Server tab's rows, built without a Context so they can be asserted
 * directly: `unitTests.returnDefaultValues` stubs android.jar, and a test that
 * touched only framework classes could pass while asserting nothing.
 *
 * See docs/decisions/2026-10-27-debug-server-tab-design.md.
 */
internal object DebugServerRows {
    private val DASHED_IPV4 = Regex("""^\d{1,3}(-\d{1,3}){3}$""")

    fun build(
        known: ServerAddressBook.KnownAddresses,
        connections: List<Connection>?,
    ): List<AddressRow> {
        val byUri = connections?.filter { it.uri != null }?.associateBy { it.uri }
        val stored = known.direct.map { it to false } + known.relay.map { it to true }
        // A session written before the address book existed has a current
        // address and no stored list; it still gets its row.
        val orphan =
            known.current
                ?.takeIf { current -> stored.none { it.first == current } }
                ?.let { listOf(it to false) }
                .orEmpty()

        return (stored + orphan).map { (uri, storedRelay) ->
            val connection = byUri?.get(uri)
            val flags = connection?.let { AddressRow.Flags(local = it.local == true, relay = it.relay == true) }
            val hostPort = connection?.let { plexTvHostPort(uri, it) } ?: hostPort(uri)
            AddressRow(
                uri = uri,
                kind = kindOf(flags, storedRelay),
                hostPort = hostPort,
                inUse = uri == known.current,
                flags = flags,
                dockerRange = isDockerRange(hostPort.removePrefix(CLEARTEXT).substringBeforeLast(':')),
            )
        }
    }

    /**
     * `https://192-168-0-2.<hash>.plex.direct:32400` reads as
     * `192.168.0.2:32400`; the hash is the same on every address and says
     * nothing about which one this is. Any other host is shown as it is.
     *
     * A cleartext address keeps its `http://`: the app refuses cleartext, and
     * without the prefix it would read exactly like the https row for the
     * same IP while never answering.
     */
    fun hostPort(uri: String): String {
        val parsed = uri.toHttpUrlOrNull() ?: return uri
        val host = parsed.host
        val label = host.substringBefore('.')
        val shown = if (host.endsWith(".plex.direct") && DASHED_IPV4.matches(label)) label.replace('-', '.') else host
        return format(shown, parsed.port, parsed.isHttps)
    }

    /** plex.tv's own address and port, when it supplied both. */
    private fun plexTvHostPort(
        uri: String,
        connection: Connection,
    ): String? {
        val address = connection.address?.takeIf { it.isNotBlank() } ?: return null
        val port = connection.port ?: return null
        return format(address, port, https = uri.startsWith("https://", ignoreCase = true))
    }

    private fun format(
        host: String,
        port: Int,
        https: Boolean,
    ): String {
        val shown = if (':' in host) "[$host]" else host
        return (if (https) "" else CLEARTEXT) + "$shown:$port"
    }

    private const val CLEARTEXT = "http://"

    private fun kindOf(
        flags: AddressRow.Flags?,
        storedRelay: Boolean,
    ): AddressKind =
        when {
            flags == null -> if (storedRelay) AddressKind.RELAY else AddressKind.DIRECT
            flags.relay -> AddressKind.RELAY
            flags.local -> AddressKind.LOCAL
            else -> AddressKind.REMOTE
        }

    /** 172.16.0.0/12, the range Docker gives its bridge networks by default. */
    private fun isDockerRange(host: String): Boolean {
        val octets = host.split('.')
        return octets.size == 4 && octets[0] == "172" && octets[1].toIntOrNull() in 16..31
    }
}
