package ai.kilopace.app.trip

/**
 * 운행 상태기계 (순수 Kotlin, Android 의존 없음 → JVM 단위테스트).
 *
 *  IDLE ──userStart──▶ OPEN ──(종료 제안 조건 | 도착 이벤트 | userEnd)──▶ CLOSING ──confirm──▶ IDLE
 *                                 ▲                                           │
 *                                 └──────────────── dismiss ──────────────────┘
 *
 * 종료 제안 조건(3조건 AND): 운전석 도어 열림 AND 정차(주행 규제 해제) AND 오도미터 정체 ≥ stallMillis.
 * 같은 운행에서 제안은 cooldownMillis 안에 반복하지 않는다(운전자 방해 방지).
 */
class TripStateMachine(
    private val stallMillisProvider: () -> Long,
    private val cooldownMillis: Long = 60_000L,
) {
    /** 고정 값으로 만들 때(테스트용) */
    constructor(stallMillis: Long, cooldownMillis: Long = 60_000L) : this({ stallMillis }, cooldownMillis)

    private val stallMillis: Long get() = stallMillisProvider()
    enum class State { IDLE, OPEN, CLOSING }
    enum class Trigger { NONE, DOOR_STALL, ARRIVED, USER }

    var state: State = State.IDLE; private set
    var odoKm: Double? = null; private set
    var lastOdoChangeAt: Long = 0L; private set
    var doorOpen: Boolean = false; private set
    var parked: Boolean = true; private set
    private var lastPromptAt: Long? = null

    /** 상태 전이 결과. suggest=true면 UI가 종료 확인을 띄운다. */
    data class Result(val suggest: Boolean, val trigger: Trigger = Trigger.NONE)

    private val none = Result(false)

    fun userStart(now: Long): Boolean {
        if (state != State.IDLE) return false
        state = State.OPEN
        lastOdoChangeAt = now
        lastPromptAt = null
        return true
    }

    fun onOdometer(km: Double, now: Long): Result {
        if (odoKm != km) { odoKm = km; lastOdoChangeAt = now }
        return none
    }

    fun onDoor(open: Boolean, now: Long): Result {
        doorOpen = open
        return if (open) evaluate(now) else none
    }

    fun onParked(parked: Boolean, now: Long): Result {
        this.parked = parked
        return if (parked) evaluate(now) else none
    }

    /** 1초 주기 틱: 도어가 이미 열린 채 정체 시간이 채워지는 경우를 잡는다. */
    fun tick(now: Long): Result = evaluate(now)

    fun onArrived(now: Long): Result {
        if (state != State.OPEN) return none
        state = State.CLOSING; lastPromptAt = now
        return Result(true, Trigger.ARRIVED)
    }

    fun userEnd(now: Long): Result {
        if (state != State.OPEN) return none
        state = State.CLOSING; lastPromptAt = now
        return Result(true, Trigger.USER)
    }

    /** 확인 완료 → 기록 저장 후 IDLE */
    fun confirm(): Boolean {
        if (state != State.CLOSING) return false
        state = State.IDLE
        return true
    }

    /** 제안 무시 → 운행 계속, 쿨다운 적용 */
    fun dismiss(now: Long) {
        if (state == State.CLOSING) { state = State.OPEN; lastPromptAt = now }
    }

    /** 운행 자체를 취소(기록 없이 IDLE) */
    fun cancelTrip() { state = State.IDLE }

    private fun evaluate(now: Long): Result {
        if (state != State.OPEN) return none
        if (!doorOpen || !parked) return none
        if (now - lastOdoChangeAt < stallMillis) return none
        val lp = lastPromptAt
        if (lp != null && now - lp < cooldownMillis) return none
        state = State.CLOSING; lastPromptAt = now
        return Result(true, Trigger.DOOR_STALL)
    }
}
