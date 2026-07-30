package com.cappielloantonio.tempo.plex.api

/**
 * The endpoint methods a Retrofit service interface declares, by name.
 *
 * Read off the annotations at runtime rather than listed by hand, so a newly
 * added endpoint appears here immediately -- which is what lets each service
 * test assert that nothing is left uncovered. Without that, an endpoint added
 * next month gets no test and nothing notices, which is exactly how the gap
 * this package's tests close came to exist.
 *
 * Filtering on "not synthetic" rather than on a list of HTTP verbs is
 * deliberate: a verb list is a blind spot the guard exists to prevent -- a
 * `@PUT` or `@DELETE` endpoint added later would carry none of the named
 * annotations, so it would be invisible to both the reflected set and the
 * hand-written expected set, and the guard would pass while that endpoint had
 * zero coverage. "Not synthetic" has no such list to fall out of date: every
 * real declared method on a Retrofit service interface is an endpoint, so
 * anything that ever appears fails the guard loudly rather than vanishing
 * from it silently, which is the safe direction for a test whose whole job is
 * catching omissions. Kotlin's `$default` bridges, emitted for interface
 * functions with default parameter values (e.g. `getResources`,
 * `getPlaylists`), are synthetic and still drop out.
 */
fun annotatedEndpoints(service: Class<*>): Set<String> =
    service.declaredMethods
        .filterNot { it.isSynthetic }
        .map { it.name }
        .toSet()
