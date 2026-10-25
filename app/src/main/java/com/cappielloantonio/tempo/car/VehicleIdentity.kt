package com.cappielloantonio.tempo.car

/** Which tier of [VehicleInputs.resolve] produced a value. */
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
        /** Nothing known: the constants tier, and what callers read before a read lands. */
        val UNKNOWN = VehicleIdentity(null, null, null, VehicleInfoSource.UNKNOWN)
    }
}

/**
 * Everything each tier is built from, as read -- uncleaned, so the Debug
 * screen can show exactly what the head unit answered.
 *
 * Each tier is taken as a unit rather than per field, so a device row never
 * pairs a real make with a placeholder model. The year is optional within the
 * vehicle tier and never survives a fall to Build. See
 * docs/decisions/2026-10-25-vehicle-identity-tier-pick-design.md for the
 * starting tier.
 */
data class VehicleInputs(
    val carMake: String?,
    val carModel: String?,
    val carYear: Int?,
    val buildManufacturer: String?,
    val buildModel: String?,
) {
    /** What [source] alone would send, or null when it has nothing to offer. */
    fun tier(source: VehicleInfoSource): VehicleIdentity? =
        when (source) {
            VehicleInfoSource.VEHICLE -> {
                whole(carMake, carModel) { make, model ->
                    // VHAL answers 0 for a property it carries without a value.
                    VehicleIdentity(make, model, carYear?.takeIf { it > 0 }, VehicleInfoSource.VEHICLE)
                }
            }

            VehicleInfoSource.BUILD -> {
                whole(buildManufacturer, buildModel) { make, model ->
                    VehicleIdentity(make, model, null, VehicleInfoSource.BUILD)
                }
            }

            VehicleInfoSource.UNKNOWN -> {
                VehicleIdentity.UNKNOWN
            }
        }

    /** The first tier from [startAt] down [LADDER] that has an answer; the last always does. */
    fun resolve(startAt: VehicleInfoSource = VehicleInfoSource.VEHICLE): VehicleIdentity =
        LADDER.drop(LADDER.indexOf(startAt)).firstNotNullOf { tier(it) }

    private inline fun whole(
        make: String?,
        model: String?,
        build: (String, String) -> VehicleIdentity,
    ): VehicleIdentity? {
        val cleanMake = make.orNullIfBlank() ?: return null
        val cleanModel = model.orNullIfBlank() ?: return null
        return build(cleanMake, cleanModel)
    }

    companion object {
        val LADDER = listOf(VehicleInfoSource.VEHICLE, VehicleInfoSource.BUILD, VehicleInfoSource.UNKNOWN)

        /** Nothing read yet: only the constants answer. */
        val NONE = VehicleInputs(null, null, null, null, null)
    }
}

/**
 * Drops control characters and answers null when nothing survives, so a
 * value that is nothing but control characters falls through the tier
 * exactly as a blank one does.
 *
 * Only below 0x20 and 0x7F go: a newline in a vendor string is header
 * injection, while everything at 0x80 and above is a name -- `Škoda`,
 * `领克` -- and rides out unchanged as UTF-8. Cleaning here rather than
 * at the transport is what keeps the Debug screen showing what is
 * actually sent. See the 2026-08-27 vehicle device name design.
 */
private fun String?.orNullIfBlank(): String? =
    this
        ?.filter { it.code >= 0x20 && it.code != 0x7F }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
