package com.cappielloantonio.tempo.car

/**
 * The reader caches process-wide and takes no injected inputs, because
 * android.car cannot be on the unit-test classpath -- so reflection is the
 * only seam. Restores what it found, since the cache outlives the test.
 */
internal fun withVehicleInputs(
    inputs: VehicleInputs?,
    body: () -> Unit,
) {
    val field = VehicleInfoReader::class.java.getDeclaredField("cached").apply { isAccessible = true }
    val previous = field.get(VehicleInfoReader)
    field.set(VehicleInfoReader, inputs)
    try {
        body()
    } finally {
        field.set(VehicleInfoReader, previous)
    }
}
