package com.cappielloantonio.tempo.plex.api

import retrofit2.http.GET
import retrofit2.http.POST

/**
 * The endpoint methods a Retrofit service interface declares, by name.
 *
 * Read off the annotations at runtime rather than listed by hand, so a newly
 * added endpoint appears here immediately -- which is what lets each service
 * test assert that nothing is left uncovered. Without that, an endpoint added
 * next month gets no test and nothing notices, which is exactly how the gap
 * this package's tests close came to exist.
 *
 * Filtering on the annotation rather than taking every declared method is
 * deliberate: Kotlin emits synthetic methods for interface functions with
 * default parameter values, and those carry no @GET or @POST.
 */
fun annotatedEndpoints(service: Class<*>): Set<String> =
    service.declaredMethods
        .filter {
            it.getAnnotation(GET::class.java) != null || it.getAnnotation(POST::class.java) != null
        }
        .map { it.name }
        .toSet()
