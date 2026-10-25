package com.cappielloantonio.tempo.car

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicReference

/**
 * Asks the vehicle what it is, once, and remembers the answer for the process.
 *
 * There is no unit test for the read and there cannot be: Robolectric loads
 * android.car but has no car service, so `Car.createCar` retries for about five
 * seconds and gives up. Every android.car type is confined to a private method
 * body -- never a field type, never a public signature. The tested half is
 * [VehicleInputs].
 *
 * See docs/decisions/2026-08-27-vehicle-device-name-design.md.
 */
object VehicleInfoReader {
    private const val TAG = "VehicleInfoReader"

    /** Global properties carry no area; INFO_* are all global. */
    private const val GLOBAL_AREA_ID = 0

    /**
     * Written by [start]'s read only while empty. Under Robolectric that read
     * lands seconds after App.onCreate, inside whichever test is running then,
     * and must not replace inputs that test put here.
     */
    private val cached = AtomicReference<VehicleInputs?>(null)

    /** What [start]'s read found, or null until it lands. */
    fun inputs(): VehicleInputs? = cached.get()

    /**
     * [VehicleIdentity.UNKNOWN] until [start]'s read lands; thereafter the
     * first tier from [startAt] down that answers. A failed vehicle read
     * leaves the vehicle tier empty, and UNKNOWN survives from VEHICLE only
     * when `Build.MANUFACTURER` and `Build.MODEL` are blank too.
     */
    fun identity(startAt: VehicleInfoSource = VehicleInfoSource.VEHICLE): VehicleIdentity =
        cached.get()?.resolve(startAt) ?: VehicleIdentity.UNKNOWN

    /**
     * Off the main thread because this connects to a system service. Called
     * from App.onCreate, which is far enough ahead of both sign-in (needs a
     * tap) and browse (needs the car's media UI) that the window in which a
     * request sees UNKNOWN is narrow.
     */
    @JvmStatic
    fun start(context: Context) {
        if (cached.get() != null) return
        Thread({ cached.compareAndSet(null, read(context)) }, "vehicle-info").start()
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
    private fun read(context: Context): VehicleInputs {
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

        return VehicleInputs(carMake, carModel, carYear, Build.MANUFACTURER, Build.MODEL)
            .also { Log.d(TAG, "vehicle inputs: $it") }
    }

    /**
     * Per-property so one unsupported id does not cost the others -- and
     * Throwable for the same reason the outer catch is: a NoSuchMethodError is
     * an Error, so catching Exception here would abandon the properties after
     * it rather than skip the one that failed.
     */
    private fun CarPropertyManager.stringOrNull(id: Int): String? =
        try {
            getProperty(String::class.java, id, GLOBAL_AREA_ID)?.value
        } catch (e: Throwable) {
            Log.d(TAG, "property $id unavailable", e)
            null
        }

    private fun CarPropertyManager.intOrNull(id: Int): Int? =
        try {
            getIntProperty(id, GLOBAL_AREA_ID)
        } catch (e: Throwable) {
            Log.d(TAG, "property $id unavailable", e)
            null
        }
}
