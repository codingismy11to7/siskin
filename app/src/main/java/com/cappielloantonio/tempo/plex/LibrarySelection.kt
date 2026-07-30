package com.cappielloantonio.tempo.plex

/**
 * The two decisions the library picker makes, as pure functions over a session.
 *
 * Separated from the browse tree because both are easy to get subtly wrong in
 * ways no emulator pass would reveal: a tick on the wrong row looks identical to
 * a tick on the right one until you own two servers, and a wrongly *kept* queue
 * only misbehaves on the next track change.
 */
object LibrarySelection {

    /**
     * Whether a (server, library) row is the one currently in use.
     *
     * Matching is on the pair, never on the section key alone: keys are small
     * integers scoped to a server, so the same key almost certainly exists on
     * every server in the account and would tick a row under a server the user
     * is merely browsing past.
     *
     * [machineIdentifier] is preferred because it survives the server changing
     * address. When the stored session predates that field, this falls back to
     * comparing [serverUri], which fails *closed* -- a server now reachable at a
     * different address shows no tick rather than the wrong one.
     */
    @JvmStatic
    fun isCurrent(
        session: PlexSession?,
        machineIdentifier: String?,
        serverUri: String,
        sectionKey: String
    ): Boolean {
        if (session == null) return false
        if (session.musicSectionKey.value != sectionKey) return false

        val stored = session.machineIdentifier
        return if (!stored.isNullOrBlank() && !machineIdentifier.isNullOrBlank()) {
            stored == machineIdentifier
        } else {
            session.serverUri == serverUri
        }
    }

    /**
     * Whether a *server* row is the one the session points at, for the tick on
     * the server list.
     *
     * Machine identifier only, with no serverUri fallback, unlike [isCurrent].
     * Listing servers deliberately does not probe them, so a server row has no
     * resolved address to compare against -- the addresses plex.tv advertises
     * for a server are a list of candidates, and the session holds the single
     * one that answered. Comparing against the wrong candidate would tick a
     * server the user is only browsing past.
     *
     * So a session saved before machineIdentifier existed shows no tick on any
     * server rather than a guess, which is the same fail-closed choice
     * [isCurrent] makes when its own identifier is missing.
     */
    @JvmStatic
    fun isCurrentServer(session: PlexSession?, machineIdentifier: String?): Boolean {
        if (session == null || machineIdentifier.isNullOrBlank()) return false
        val stored = session.machineIdentifier
        return !stored.isNullOrBlank() && stored == machineIdentifier
    }

    /**
     * Whether committing [new] makes the saved queue meaningless.
     *
     * Only a change of *server* does. Plex rating keys are server-wide rather
     * than section-scoped, so moving between two libraries on one server leaves
     * every queued item valid. Stream URLs, by contrast, are rebuilt from a
     * stored partKey against the current serverUri -- so after a server change
     * every queued partKey would be rebuilt against a server it never came from,
     * yielding 404s or, worse, a valid URL for an unrelated track.
     */
    @JvmStatic
    fun invalidatesQueue(old: PlexSession?, new: PlexSession): Boolean {
        if (old == null) return false
        val oldId = old.machineIdentifier
        val newId = new.machineIdentifier
        return if (!oldId.isNullOrBlank() && !newId.isNullOrBlank()) {
            oldId != newId
        } else {
            old.serverUri != new.serverUri
        }
    }
}
