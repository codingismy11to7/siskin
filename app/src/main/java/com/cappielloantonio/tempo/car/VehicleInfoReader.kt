package com.cappielloantonio.tempo.car

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Asks the vehicle what it is, once, and remembers the answer for the process.
 *
 * There is no unit test for this class and there cannot be: android.car.jar is
 * not on the unit-test classpath and Robolectric has no shadow for it. Every
 * android.car type is therefore confined to a private method body -- never a
 * field type, never a public signature -- so the class still loads in
 * Robolectric tests that construct PlexApi without calling [start]. The tested
 * half is [VehicleIdentity.resolve].
 *
 * See docs/decisions/2026-08-27-vehicle-device-name-design.md.
 */
object VehicleInfoReader {
    private const val TAG = "VehicleInfoReader"

    /** Global properties carry no area; INFO_* are all global. */
    private const val GLOBAL_AREA_ID = 0

    @Volatile
    private var resolved: VehicleIdentity? = null

    /** [VehicleIdentity.UNKNOWN] until [start]'s read lands, and after it fails. */
    fun identity(): VehicleIdentity = resolved ?: VehicleIdentity.UNKNOWN

    /**
     * Off the main thread because this connects to a system service. Called
     * from App.onCreate, which is far enough ahead of both sign-in (needs a
     * tap) and browse (needs the car's media UI) that the window in which a
     * request sees UNKNOWN is narrow.
     */
    @JvmStatic
    fun start(context: Context) {
        if (resolved != null) return
        Thread({ resolved = read(context) }, "vehicle-info").start()
    }

    /**
     * Absorbs every failure rather than propagating one. These headers ride on
     * every request including the first POST /pins, so a car that will not
     * answer must never be able to fail a request.
     *
     * Throwable, not Exception, and the difference is load-bearing: compileSdk
     * is 37 while minSdk is 28, so an android.car method newer than the head
     * unit's runtime throws NoSuchMethodError -- an Error, which catch
     * (Exception) does not see. That is not hypothetical. It killed this
     * thread, crash-looped the app and wedged com.android.car.media before the
     * getCarManager call below was pinned to the overload every version has.
     *
     * Note this is nowhere near an `either { }` block, so Arrow's raise hazard
     * does not apply.
     */
    private fun read(context: Context): VehicleIdentity {
        var carMake: String? = null
        var carModel: String? = null
        var carYear: Int? = null
        var car: Car? = null

        try {
            car = Car.createCar(context)
            // Not the Class<T> overload: that one is compileSdk 37's addition and
            // is absent from the API 33 emulator's actual android.car.jar, which
            // throws NoSuchMethodError -- an Error, so the catch below never sees
            // it. The String-keyed overload is on every android.car.jar back to
            // this project's minSdk.
            val properties = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            if (properties != null) {
                carMake = properties.stringOrNull(VehiclePropertyIds.INFO_MAKE)
                carModel = properties.stringOrNull(VehiclePropertyIds.INFO_MODEL)
                carYear = properties.intOrNull(VehiclePropertyIds.INFO_MODEL_YEAR)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "vehicle info unavailable", e)
        } finally {
            try {
                car?.disconnect()
            } catch (e: Throwable) {
                Log.w(TAG, "car disconnect failed", e)
            }
        }

        return VehicleIdentity
            .resolve(carMake, carModel, carYear, Build.MANUFACTURER, Build.MODEL)
            .also { Log.d(TAG, "vehicle identity: $it") }
    }

    /** Per-property so one unsupported id does not cost the others. */
    private fun CarPropertyManager.stringOrNull(id: Int): String? =
        try {
            getProperty(String::class.java, id, GLOBAL_AREA_ID)?.value
        } catch (e: Exception) {
            Log.d(TAG, "property $id unavailable", e)
            null
        }

    private fun CarPropertyManager.intOrNull(id: Int): Int? =
        try {
            getIntProperty(id, GLOBAL_AREA_ID)
        } catch (e: Exception) {
            Log.d(TAG, "property $id unavailable", e)
            null
        }
}
