package com.cappielloantonio.tempo.util

import org.junit.Assert.fail
import org.robolectric.shadows.ShadowLog

/**
 * Logcat is readable over adb and lands in bug reports, release builds
 * included, so a line holding a Plex token leaks a working credential.
 * The HTTP logger redacts the header; a token in a URL query gets past it.
 */
object LoggedTokenCheck {
    /**
     * Searches for the token's value rather than the parameter name, which a
     * header dump or a URL-encoded nested URL spells differently. Pick a token
     * URL encoding leaves unchanged. Throwables are searched down the cause
     * chain, since logcat prints every `Caused by:`.
     */
    fun assertTokenNotLogged(token: String) {
        val leaks =
            ShadowLog.getLogs().filter { line ->
                token in line.msg ||
                    generateSequence(line.throwable) { it.cause }.any { token in it.toString() }
            }
        if (leaks.isNotEmpty()) {
            fail("Logged the Plex token:\n" + leaks.joinToString("\n") { "${it.tag}: ${it.msg}" })
        }
    }
}
