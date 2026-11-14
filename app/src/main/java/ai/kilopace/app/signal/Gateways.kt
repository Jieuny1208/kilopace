package ai.kilopace.app.signal

/**
 * 차량 신호 추상화. Pleos 구현(PleosVehicleGateway)과 테스트용 가짜 구현을 바꿔 끼울 수 있다.
 * 모든 콜백은 메인 스레드에서 호출된다.
 */
interface VehicleGateway {
    interface Listener {
        /** 계기판 누적거리(km). 값이 바뀌었을 때만 호출된다. */
        fun onOdometer(km: Double)
        /** 운전석 도어 상태. open=true 열림 */
        fun onDriverDoor(open: Boolean)
        /** 운전석 안전벨트. true=체결, false=미착용, null=값 없음 */
        fun onDriverSeatBelt(buckled: Boolean?)
        fun onVehicleError(api: String, message: String)
    }

    /** 연결·구독 시작. 지원 여부는 [odometerSupported]로 알린다. */
    fun start(listener: Listener)
    fun stop()

    /** 즉시 1회 조회. null = 미지원·미수신 */
    fun readOdometer(onResult: (Double?) -> Unit)
    val odometerSupported: Boolean?
    val lastOdometerKm: Double?
    val vin: String?
    /** 운전석 벨트. null = 미지원·미수신(차단하지 않음) */
    val driverBeltBuckled: Boolean?
}

interface NaviGateway {
    data class Place(val name: String, val address: String, val lat: Double? = null, val lng: Double? = null)

    interface Listener {
        /** 내비 목적지 정보 수신 (설정·변경 시) */
        fun onDestination(place: Place?)
        /** 내비 현재 위치 주소 수신 */
        fun onCurrentLocation(place: Place)
        /** 경로 안내 시작: 목적지 이름과 내비가 계산한 편도 경로 거리(km) */
        fun onRouteStarted(name: String, oneWayKm: Double)
        /** 목적지 도착 이벤트 */
        fun onArrived(place: Place, distanceM: Int, durationS: Int)
    }

    fun start(listener: Listener)
    fun stop()
    fun requestDestination()
    fun requestCurrentLocation()
    fun requestRecentDestination()
    val connected: Boolean
    val lastDestination: Place?
    val lastCurrentLocation: Place?
    val lastRecentDestination: Place?
    /** 안내 중인 경로의 목적지 이름과 편도 거리(km). 안내 중이 아니면 null */
    val lastRouteName: String?
    val lastRouteKm: Double?
}

interface LocationGateway {
    data class Fix(val lat: Double, val lng: Double, val timestampMillis: Long)
    fun start()
    fun stop()
    fun lastKnown(onResult: (Fix?) -> Unit)
    val supported: Boolean
}
