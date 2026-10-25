package com.cappielloantonio.tempo.car

import java.util.concurrent.atomic.AtomicReference

/**
 * The reader caches process-wide and takes no injected inputs, because its
 * read needs a car service no test has -- so reflection is the only seam.
 * Restores what it found, since the cache outlives the test.
 */
internal fun withVehicleInputs(
    inputs: VehicleInputs?,
    body: () -> Unit,
) {
    val field = VehicleInfoReader::class.java.getDeclaredField("cached").apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    val cached = field.get(VehicleInfoReader) as AtomicReference<VehicleInputs?>
    val previous = cached.getAndSet(inputs)
    try {
        body()
    } finally {
        cached.set(previous)
    }
}
