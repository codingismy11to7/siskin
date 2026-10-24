package com.cappielloantonio.tempo.plex.auth

/**
 * The plex.tv account a pass through the pickers will commit: the one that
 * approved the PIN, the Home member switched to, or -- entered from settings
 * or the debug screen -- the stored one.
 *
 * Carried in the flow's states rather than written, so backing out of any
 * picker leaves the stored session exactly as it was. Every plex.tv call the
 * flow makes uses [token] explicitly; the stored account token may belong to
 * someone else until the commit.
 */
data class PendingAccount(
    val token: String,
) {
    /** States are data classes, and a credential has no business in their printout. */
    override fun toString(): String = "PendingAccount(token=redacted)"
}
