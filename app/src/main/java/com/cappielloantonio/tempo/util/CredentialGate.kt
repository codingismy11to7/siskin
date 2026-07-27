package com.cappielloantonio.tempo.util

/**
 * The single definition of "Siskin is signed in".
 *
 * This lived inline in MainActivity.init(). The AAOS browse gate needs the same
 * answer the instant the car asks for a library, and two copies of the rule would
 * drift apart.
 */
object CredentialGate {

    /**
     * Subsonic error codes meaning the credentials themselves were rejected, so
     * signing in again can plausibly fix it. Codes like 30 (server must upgrade)
     * are failures that a new password will not repair.
     */
    private val AUTH_FAILURE_CODES = setOf(40, 41, 50)

    @JvmStatic
    fun isSignedIn(server: String?, password: String?, token: String?, salt: String?): Boolean {
        if (server.isNullOrBlank()) return false
        if (!password.isNullOrBlank()) return true
        return !token.isNullOrBlank() && !salt.isNullOrBlank()
    }

    @JvmStatic
    fun isSignedIn(): Boolean = isSignedIn(
        Preferences.getServer(),
        Preferences.getPassword(),
        Preferences.getToken(),
        Preferences.getSalt()
    )

    @JvmStatic
    fun isAuthFailure(subsonicErrorCode: Int?): Boolean =
        subsonicErrorCode != null && AUTH_FAILURE_CODES.contains(subsonicErrorCode)
}
