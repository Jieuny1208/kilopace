package ai.kilopace.app.signal

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.CarInfo
import ai.pleos.playground.vehicle.api.Door
import ai.pleos.playground.vehicle.api.Odometer
import ai.pleos.playground.vehicle.api.Seat
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Vehicle SDK 2.3.2 구현. 2026-10-26 에뮬레이터 실험(OdoProbe)으로 확인한 동작:
 *  - checkCurrentDistanceDrivenCapabilities → READ, getCurrentDistanceDriven = Virtual Console Odometer 값(km)
 *  - registerDistanceDriven 콜백은 값이 안 바뀌어도 3~5초마다 반복 호출 → 중복 값 필터링
 *  - Trip 계열 API는 에뮬레이터에서 ERROR_NOT_SUPPORTED_PROPERTY → 사용하지 않음
 */
class PleosVehicleGateway(context: Context) : VehicleGateway {

    companion object { private const val TAG = "KiloPace/Vehicle" }

    private val vehicle = Vehicle(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private var odometer: Odometer? = null
    private var door: Door? = null
    private var seat: Seat? = null
    private var carInfo: CarInfo? = null
    private var listener: VehicleGateway.Listener? = null
    private var started = false

    override var odometerSupported: Boolean? = null; private set
    override var lastOdometerKm: Double? = null; private set
    override var vin: String? = null; private set
    override var driverBeltBuckled: Boolean? = null; private set

    private val driverDoorArea = DoorArea(DoorValue.ROW_1_LEFT)
    private val driverSeatArea = SeatArea(SeatValue.ROW_1_LEFT)

    private val beltListener = object : SeatBeltBuckledStateListener {
        override fun onSeatBeltBuckledStateUpdated(state: Boolean?) {
            main.post {
                if (driverBeltBuckled != state) { driverBeltBuckled = state; listener?.onDriverSeatBelt(state) }
            }
        }
        override fun onFailed(e: Exception) = fail("registerSeatBeltBuckledState", e)
    }

    private val odoListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(value: Float?) {
            val km = value?.toDouble() ?: return
            main.post {
                if (lastOdometerKm != km) {
                    lastOdometerKm = km
                    listener?.onOdometer(km)
                }
            }
        }
        override fun onFailed(e: Exception) = fail("registerDistanceDriven", e)
    }

    private val doorListener = object : DoorPositionListener {
        override fun onPositionUpdated(pos: Int?) {
            val open = (pos ?: 0) > 0
            main.post { listener?.onDriverDoor(open) }
        }
        override fun onFailed(e: Exception) = fail("registerDoorPosition", e)
    }

    override fun start(listener: VehicleGateway.Listener) {
        this.listener = listener
        if (started) return
        try {
            vehicle.initialize()
            odometer = vehicle.getOdometer()
            door = vehicle.getDoor()
            seat = vehicle.getSeat()
            carInfo = vehicle.getCarInfo()
            started = true
        } catch (e: Exception) {
            fail("Vehicle.initialize", e); return
        }
        val odo = odometer ?: return
        safe("checkCurrentDistanceDrivenCapabilities") {
            odo.checkCurrentDistanceDrivenCapabilities(
                onComplete = { cfg ->
                    val ok = cfg.accessType == AccessType.READ || cfg.accessType == AccessType.READ_WRITE
                    main.post { odometerSupported = ok }
                    if (ok) {
                        safe("getCurrentDistanceDriven") { odo.getCurrentDistanceDriven(
                            onComplete = { v -> v?.let { odoListener.onDistanceDrivenUpdated(it) } },
                            onFailed = { e -> fail("getCurrentDistanceDriven", e) }) }
                        safe("registerDistanceDriven") { odo.registerDistanceDriven(odoListener) }
                    }
                },
                onFailed = { e -> main.post { odometerSupported = false }; fail("checkCurrentDistanceDrivenCapabilities", e) }
            )
        }
        door?.let { d ->
            safe("getCurrentDoorPosition") { d.getCurrentDoorPosition(
                area = driverDoorArea,
                onComplete = { pos -> doorListener.onPositionUpdated(pos) },
                onFailed = { e -> fail("getCurrentDoorPosition", e) }) }
            safe("registerDoorPosition") { d.registerDoorPosition(driverDoorArea, doorListener) }
        }
        seat?.let { s ->
            safe("getCurrentSeatBeltBuckledState") { s.getCurrentSeatBeltBuckledState(
                area = driverSeatArea,
                onComplete = { b -> beltListener.onSeatBeltBuckledStateUpdated(b) },
                onFailed = { e -> Log.i(TAG, "seat belt state unavailable: ${e.message}") }) }
            safe("registerSeatBeltBuckledState") { s.registerSeatBeltBuckledState(driverSeatArea, beltListener) }
        }
        carInfo?.let { c ->
            safe("getVINInfo") { c.getVINInfo(
                onComplete = { v -> main.post { vin = v } },
                onFailed = { e -> Log.i(TAG, "VIN unavailable: ${e.message}") }) }
        }
    }

    override fun readOdometer(onResult: (Double?) -> Unit) {
        val odo = odometer
        if (odo == null || odometerSupported == false) { onResult(null); return }
        safe("getCurrentDistanceDriven") {
            odo.getCurrentDistanceDriven(
                onComplete = { v -> main.post { v?.let { lastOdometerKm = it.toDouble() }; onResult(v?.toDouble()) } },
                onFailed = { e -> fail("getCurrentDistanceDriven", e); main.post { onResult(null) } }
            )
        }
    }

    override fun stop() {
        if (!started) return
        safe("unregisterDistanceDriven") { odometer?.unregisterDistanceDriven(odoListener) }
        safe("unregisterDoorPosition") { door?.unregisterDoorPosition(driverDoorArea, doorListener) }
        safe("unregisterSeatBeltBuckledState") { seat?.unregisterSeatBeltBuckledState(driverSeatArea, beltListener) }
        safe("Vehicle.release") { vehicle.release() }
        started = false
        listener = null
    }

    private fun fail(api: String, e: Exception) {
        Log.w(TAG, "$api failed: ${e.message}")
        main.post { listener?.onVehicleError(api, e.message ?: e::class.java.simpleName) }
    }

    private inline fun safe(api: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { fail(api, e) }
    }
}
