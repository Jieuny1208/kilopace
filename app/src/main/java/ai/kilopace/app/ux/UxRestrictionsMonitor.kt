package ai.kilopace.app.ux

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log

/**
 * AAOS CarUxRestrictions 연동(Pleos 주행 규제 개발 가이드). restricted=true면 목록·입력·내보내기 UI를 잠근다.
 * Car API가 없는 환경(일반 에뮬레이터)에서는 restricted=false로 동작한다.
 */
class UxRestrictionsMonitor(private val context: Context) {

    companion object { private const val TAG = "KiloPace/Ux" }

    var restricted: Boolean = false; private set
    var available: Boolean = false; private set
    private val listeners = mutableListOf<(Boolean) -> Unit>()
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    fun addListener(l: (Boolean) -> Unit) { listeners += l; l(restricted) }
    fun removeListener(l: (Boolean) -> Unit) { listeners -= l }

    fun start() {
        try {
            val c = Car.createCar(context.applicationContext) ?: run { Log.i(TAG, "Car unavailable"); return }
            car = c
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager ?: return
            manager = m
            available = true
            m.currentCarUxRestrictions?.let { apply(it) }
            m.registerListener { r -> apply(r) }
        } catch (t: Throwable) {
            // NoClassDefFoundError 포함: android.car 미제공 환경
            Log.i(TAG, "CarUxRestrictions unavailable: ${t.message}")
            available = false
        }
    }

    private fun apply(r: CarUxRestrictions) {
        val now = r.isRequiresDistractionOptimization
        if (now != restricted) {
            restricted = now
            listeners.toList().forEach { it(now) }
        }
    }

    fun stop() {
        try { manager?.unregisterListener() } catch (_: Throwable) {}
        try { car?.disconnect() } catch (_: Throwable) {}
        manager = null; car = null
    }
}
