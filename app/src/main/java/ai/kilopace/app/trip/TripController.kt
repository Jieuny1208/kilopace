package ai.kilopace.app.trip

import ai.kilopace.app.data.Estimate
import ai.kilopace.app.data.Impact
import ai.kilopace.app.data.Pace
import ai.kilopace.app.data.PaceCalc
import ai.kilopace.app.data.PlanStore
import ai.kilopace.app.data.Settings
import ai.kilopace.app.data.Speech
import ai.kilopace.app.data.SpeechText
import ai.kilopace.app.data.TripEstimator
import ai.kilopace.app.data.TripRecord
import ai.kilopace.app.data.TripStore
import ai.kilopace.app.data.VoiceQuery
import ai.kilopace.app.signal.LocationGateway
import ai.kilopace.app.signal.NaviGateway
import ai.kilopace.app.signal.VehicleGateway
import ai.kilopace.app.ux.UxRestrictionsMonitor
import ai.kilopace.app.voice.VoiceInput
import ai.kilopace.app.voice.VoicePrompter
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * 앱 수명 동안 살아 있는 오케스트레이터(Application 스코프). SDK 게이트웨이 → 상태기계·약정 계산 → 저장소 → UI·음성.
 */
class TripController(
    private val vehicle: VehicleGateway,
    private val navi: NaviGateway,
    private val location: LocationGateway,
    private val voice: VoicePrompter,
    private val voiceInput: VoiceInput,
    private val ux: UxRestrictionsMonitor,
    private val store: TripStore,
    private val plans: PlanStore,
    private val settings: Settings,
) {
    companion object { private const val TAG = "KiloPace/Trip" }

    interface UiListener {
        fun onStateChanged()
        /** STT 중간/최종 텍스트 */
        fun onVoiceText(text: String?, final: Boolean)
        fun onMessage(text: String)
    }

    /** 목적지를 넣었을 때의 판단 결과 */
    data class Advice(
        val dest: NaviGateway.Place,
        /** null = 왕복 거리를 추정할 근거가 없음 */
        val estimate: Estimate?,
        val impacts: List<Impact>,
        val spoken: String,
    ) {
        val crosses: Boolean get() = impacts.any { it.crosses }
    }

    private val main = Handler(Looper.getMainLooper())
    // 설정의 정체 시간을 매번 읽어 재시작 없이 반영한다
    private val sm = TripStateMachine(stallMillisProvider = { settings.stallSeconds * 1000L })
    private var ui: UiListener? = null
    private var ticking = false
    /** 같은 목적지에 대해 두 번 말하지 않기 위한 키 */
    private var spokenDestKey: String? = null
    private var lastFix: LocationGateway.Fix? = null

    val state: TripStateMachine.State get() = sm.state
    val odometerKm: Double? get() = vehicle.lastOdometerKm ?: sm.odoKm
    val odometerSupported: Boolean? get() = vehicle.odometerSupported
    val doorOpen: Boolean get() = sm.doorOpen
    val parked: Boolean get() = !ux.restricted
    val destination: NaviGateway.Place? get() = navi.lastDestination
    val naviConnected: Boolean get() = navi.connected
    var currentTrip: TripRecord? = null; private set
    var lastVehicleError: String? = null; private set
    var advice: Advice? = null; private set
    /** 마지막으로 차가 말한 문장 (화면에도 보여 준다) */
    var lastSpoken: String = ""; private set

    /** 운행 중 거리 (현재 계기판 − 시작 계기판) */
    val liveDistanceKm: Double?
        get() {
            val s = currentTrip?.odoStartKm ?: return null
            val o = odometerKm ?: return null
            return if (o < s) null else o - s
        }

    /** 등록된 약정들의 현재 상태. 계기판 값을 아직 못 받았으면 빈 목록 */
    fun paces(): List<Pace> {
        val odo = odometerKm ?: return emptyList()
        val now = now()
        return plans.all().map { PaceCalc.pace(it, odo, now) }
    }

    /** 1초 틱은 운행 중에만 돈다. IDLE로 돌아오면 스스로 멈춘다. */
    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            if (sm.state == TripStateMachine.State.IDLE) { ticking = false; return }
            handle(sm.tick(now()))
            main.postDelayed(this, 1_000L)
        }
    }

    private fun ensureTicking() {
        if (ticking) return
        ticking = true; main.post(tick)
    }

    fun start() {
        vehicle.start(object : VehicleGateway.Listener {
            override fun onOdometer(km: Double) {
                handle(sm.onOdometer(km, now()))
                evaluateDestination(speak = true)   // 계기판 값이 목적지보다 늦게 온 경우를 잡는다
                ui?.onStateChanged()
            }
            override fun onDriverDoor(open: Boolean) { handle(sm.onDoor(open, now())); ui?.onStateChanged() }
            override fun onDriverSeatBelt(buckled: Boolean?) {}
            override fun onVehicleError(api: String, message: String) { lastVehicleError = "$api: $message"; ui?.onStateChanged() }
        })
        navi.start(object : NaviGateway.Listener {
            override fun onDestination(place: NaviGateway.Place?) {
                if (place == null) { advice = null; spokenDestKey = null } else evaluateDestination(speak = true)
                ui?.onStateChanged()
            }
            override fun onCurrentLocation(place: NaviGateway.Place) {
                currentTrip?.let { t -> if (t.startAddr.isBlank() && t.isOpen) { currentTrip = t.copy(startAddr = place.address); store.upsert(currentTrip!!) } }
                evaluateDestination(speak = true)
                ui?.onStateChanged()
            }
            override fun onRouteStarted(name: String, oneWayKm: Double) { evaluateDestination(speak = true); ui?.onStateChanged() }
            override fun onArrived(place: NaviGateway.Place, distanceM: Int, durationS: Int) { handle(sm.onArrived(now())) }
        })
        location.start()
        voice.start()
        voiceInput.start()
        ux.start()
        ux.addListener { restricted ->
            // 주행 규제가 걸리면(기어가 P를 벗어나면) 운행을 스스로 시작한다
            if (restricted && sm.state == TripStateMachine.State.IDLE) startTrip()
            handle(sm.onParked(!restricted, now()))
            ui?.onStateChanged()
        }
        plans.addListener { evaluateDestination(speak = false); ui?.onStateChanged() }

        // 앱 재시작 시 저장된 운행 복원
        store.openTrip()?.let { t ->
            currentTrip = t
            sm.userStart(now())
            ensureTicking()
        }
    }

    fun stop() {
        ticking = false; main.removeCallbacks(tick)
        voiceInput.release(); voice.release()
        ux.stop(); location.stop(); navi.stop(); vehicle.stop()
    }

    fun attach(listener: UiListener) { ui = listener; listener.onStateChanged() }
    fun detach(listener: UiListener) { if (ui === listener) ui = null }

    // ---- 목적지 경고 (핵심) ----

    /**
     * 내비 목적지와 지금 계기판으로 "다녀오면 약정을 넘는가"를 계산한다.
     * 같은 목적지는 한 번만 말한다. 계기판·현재 위치가 늦게 도착하면 그때 다시 계산한다.
     */
    private fun evaluateDestination(speak: Boolean) {
        // 안내가 시작되면 경로 이름·거리가 먼저 오고, 주소·좌표는 뒤따라 온다
        val routeName = navi.lastRouteName?.takeIf { it.isNotBlank() }
        val dest = navi.lastDestination?.takeIf { it.name.isNotBlank() || it.address.isNotBlank() }
            ?: routeName?.let { NaviGateway.Place(it, "") } ?: return
        val routeKm = navi.lastRouteKm?.takeIf { routeName != null && (dest.name.isBlank() || dest.name == routeName) }
        val odo = odometerKm ?: return
        val cur = navi.lastCurrentLocation
        val est = TripEstimator.roundTrip(
            dest.name, dest.lat, dest.lng,
            cur?.lat ?: lastFix?.lat, cur?.lng ?: lastFix?.lng, store.closed(), routeKm,
        )
        val now = now()
        val impacts = if (est == null) emptyList() else plans.all().map { PaceCalc.impact(it, odo, est.roundTripKm, now) }
        val name = dest.name.ifBlank { dest.address }
        val text = if (est == null) "" else Speech.destinationWarning(name, est, impacts)
        advice = Advice(dest, est, impacts, text)
        val key = "$name|${est?.source}|${est?.roundTripKm?.toLong()}"
        if (speak && est != null && impacts.isNotEmpty() && key != spokenDestKey) {
            spokenDestKey = key
            say(text)
        }
    }

    // ---- 운행 ----

    /** 운행 시작. 계기판 값을 읽어 출발값으로 남긴다. */
    fun startTrip() {
        if (sm.state != TripStateMachine.State.IDLE) return
        if (!sm.userStart(now())) return
        ensureTicking()
        val id = UUID.randomUUID().toString()
        val t0 = TripRecord(
            id = id, startAt = now(), endAt = null,
            odoStartKm = odometerKm, odoEndKm = null,
            destName = "", destAddr = "",
            startAddr = navi.lastCurrentLocation?.address.orEmpty(),
        )
        currentTrip = t0
        store.upsert(t0)
        vehicle.readOdometer { read ->
            // 즉시 조회 값이 있으면 그것으로 출발값을 확정한다
            val c = currentTrip
            if (read != null && c != null && c.id == id && c.odoStartKm == null) {
                currentTrip = c.copy(odoStartKm = read); store.upsert(currentTrip!!)
            }
            ui?.onStateChanged()
        }
        navi.requestCurrentLocation(); navi.requestDestination()
        location.lastKnown { fix -> if (fix != null) lastFix = fix }
        ui?.onStateChanged()
        ui?.onMessage("운행 시작 · 계기판 ${ai.kilopace.app.data.Fmt.km(odometerKm)} km")
    }

    /** '도착' 버튼 */
    fun requestEnd() { handle(sm.userEnd(now())) }

    /** 운행을 닫고 한 줄로 알려 준다. 묻지 않는다. */
    private fun closeTrip() {
        val t = currentTrip
        if (t == null) { sm.confirm(); ui?.onStateChanged(); return }
        vehicle.readOdometer { read ->
            val start = t.odoStartKm
            val candidate = read ?: odometerKm
            val odoEnd = if (start != null && candidate != null && candidate < start) null else candidate
            val dest = navi.lastDestination
            val closed = t.copy(
                endAt = now(), odoEndKm = odoEnd,
                destName = dest?.name.orEmpty(),
                destAddr = dest?.address.orEmpty().ifBlank { navi.lastCurrentLocation?.address.orEmpty() },
            )
            val km = closed.distanceKm ?: 0.0
            currentTrip = null
            sm.confirm()
            if (km <= 0.0) {
                // 움직이지 않은 운행은 남기지 않는다
                store.delete(closed.id)
                ui?.onStateChanged()
                ui?.onMessage("이동 거리가 없어 기록하지 않았습니다.")
                return@readOdometer
            }
            store.upsert(closed)
            evaluateDestination(speak = false)
            ui?.onStateChanged()
            val line = Speech.tripEnd(km, paces())
            ui?.onMessage(line)
            say(line)
        }
    }

    // ---- 음성 질문 ----

    /** 버튼으로 바로 듣기 */
    fun answer(intent: VoiceQuery.Intent) {
        val p = paces()
        // 운전자가 방금 물은 것이므로 앞의 안내를 멈추고 바로 답한다
        say(if (intent == VoiceQuery.Intent.PROJECTION) Speech.projection(p) else Speech.remaining(p), interrupt = true)
        ui?.onStateChanged()
    }

    /** 마이크로 묻기: 한마디를 듣고 대답한다. 못 알아들으면 남은 거리로 답한다. */
    fun askByVoice() {
        voice.silence()
        voiceInput.listen(
            partial = { ui?.onVoiceText(it, false) },
            finished = { text ->
                ui?.onVoiceText(text, true)
                answer(VoiceQuery.classify(text))
            }
        )
    }

    private fun say(text: String, interrupt: Boolean = false) {
        if (text.isBlank()) return
        lastSpoken = text
        Log.i(TAG, "say: $text")
        if (settings.voiceEnabled) voice.say(SpeechText.sentences(text).map { SpeechText.forTts(it) }, interrupt)
    }

    // ---- internal ----

    private fun handle(r: TripStateMachine.Result) {
        if (!r.suggest) return
        Log.i(TAG, "trip end: ${r.trigger}")
        closeTrip()
    }

    private fun now() = System.currentTimeMillis()
}
