package com.cappielloantonio.tempo.car

/** Which tier of [VehicleIdentity.resolve] produced a value. */
enum class VehicleInfoSource {
    VEHICLE,
    BUILD,
    UNKNOWN,
}

/**
 * What the head unit says the car is.
 *
 * Vehicle vocabulary only -- the mapping onto X-Plex-* headers lives in
 * PlexIdentity, so this package never learns that Plex exists. See
 * docs/decisions/2026-08-27-vehicle-device-name-design.md.
 */
data class VehicleIdentity(
    val make: String?,
    val model: String?,
    val year: Int?,
    val source: VehicleInfoSource,
) {
    companion object {
        /** Nothing known: what [resolve] answers, and what callers read before a read lands. */
        val UNKNOWN = VehicleIdentity(null, null, null, VehicleInfoSource.UNKNOWN)

        /**
         * Picks a tier as a unit rather than per field, so a device row never
         * pairs a real make with a placeholder model. The year is optional
         * within the vehicle tier and never survives a fall to [Build].
         */
        fun resolve(
            carMake: String?,
            carModel: String?,
            carYear: Int?,
            buildManufacturer: String?,
            buildModel: String?,
        ): VehicleIdentity {
            val vehicleMake = carMake.orNullIfBlank()
            val vehicleModel = carModel.orNullIfBlank()
            if (vehicleMake != null && vehicleModel != null) {
                return VehicleIdentity(
                    make = vehicleMake,
                    model = vehicleModel,
                    // VHAL answers 0 for a property it carries without a value.
                    year = carYear?.takeIf { it > 0 },
                    source = VehicleInfoSource.VEHICLE,
                )
            }

            val fallbackMake = buildManufacturer.orNullIfBlank()
            val fallbackModel = buildModel.orNullIfBlank()
            if (fallbackMake != null && fallbackModel != null) {
                return VehicleIdentity(fallbackMake, fallbackModel, null, VehicleInfoSource.BUILD)
            }

            return UNKNOWN
        }

        private fun String?.orNullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
    }
}
