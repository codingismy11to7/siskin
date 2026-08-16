package com.cappielloantonio.tempo.provider

import androidx.annotation.VisibleForTesting
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs one body at a time per key, and different keys at once.
 *
 * This exists for [CompositeArt.build]. That provider is exported and
 * served on a thread pool, so the car can open the same decade tile
 * concurrently, and until one build renames its file into place every one of
 * those opens is a fresh cache miss -- a full Plex metadata query plus four
 * cover transcodes, each, for one image. The album artwork path never needed
 * this because Glide's engine dedups identical in-flight requests underneath
 * it; the metadata query is the part Glide knows nothing about.
 *
 * **Per key, not global.** On the first browse of an hour all eight decade
 * tiles miss at once against an executor sized `max(2, cores / 2)`. Serialising
 * those behind one lock would turn a burst into eight sequential round trips,
 * which is a worse problem than the one being fixed. Only repeats of the *same*
 * tile wait.
 *
 * **A lock is not a correctness mechanism here.** Two builds of one tile
 * overlapping was already safe -- each writes a uniquely named partial and
 * renames, so the race is last-writer-wins between two complete files -- and
 * that stays the backstop. This only stops the second build from being started.
 */
object CompositeBuildLocks {

    private val locks = ConcurrentHashMap<String, Any>()

    /**
     * Holds [key]'s lock for the duration of [body].
     *
     * Entries do not accumulate across hours: removal runs in a `finally` on
     * every exit, so it is the *first* thread out of a key that removes it,
     * not the last -- a later holder of that same lock object can still be
     * inside [body] when the mapping disappears out from under it. The
     * removal is the two-argument `remove(key, value)`, which only fires if
     * the mapping is still the lock this call acquired -- a thread that
     * removes late cannot take away a lock some newer builder has already
     * installed under the same key. The cost of that early removal is that a
     * racing pair can briefly hold different locks for one key and both run;
     * the loser then finds the file already cached, and the rename backstop
     * covers the rest. Bounding the map is worth that, because a key carries
     * a bucket and every hour mints eight fresh ones. Put plainly: this is
     * best-effort deduplication, not mutual exclusion for all time --
     * correctness across the removal window rests on the unique-partial-file
     * plus `renameTo` backstop described on the object, not on this lock.
     */
    fun <T> exclusively(key: String, body: () -> T): T {
        val lock = locks.computeIfAbsent(key) { Any() }
        try {
            return synchronized(lock) { body() }
        } finally {
            locks.remove(key, lock)
        }
    }

    /** How many keys are currently held, for the test that pins that they do
     * not accumulate. */
    @VisibleForTesting
    fun heldKeys(): Int = locks.size
}
