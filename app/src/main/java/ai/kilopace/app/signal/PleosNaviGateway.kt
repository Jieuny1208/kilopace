package ai.kilopace.app.signal

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * NaviHelper SDK 3.0.5 구현. 읽기(NAVI_ROUTE) API만 사용한다.
 * 에뮬레이터에서는 차량이 경로를 따라 이동하지 않아 onDestinationArrived가 오지 않을 수 있다(SoftRide 관측).
 */
class PleosNaviGateway(context: Context) : NaviGateway {

    companion object { private const val TAG = "KiloPace/Navi" }

    private val navi = NaviHelper(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private var listener: NaviGateway.Listener? = null
    private var started = false

    override var connected: Boolean = false; private set
    override var lastDestination: NaviGateway.Place? = null; private set
    override var lastCurrentLocation: NaviGateway.Place? = null; private set
    override var lastRecentDestination: NaviGateway.Place? = null; private set
    override var lastRouteName: String? = null; private set
    override var lastRouteKm: Double? = null; private set

    private val events = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            val p = NaviGateway.Place(destinationInfo.name, destinationInfo.address, destinationInfo.latitude, destinationInfo.longitude)
            main.post { lastDestination = p; listener?.onDestination(p) }
        }
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            // 목적지를 넣고 안내를 시작한 순간. 내비가 계산한 실제 경로 거리(m)가 함께 온다.
            val name = routeStartInfo.name
            val km = routeStartInfo.distance / 1000.0
            main.post {
                lastRouteName = name; lastRouteKm = km
                listener?.onRouteStarted(name, km)
                requestDestination()
            }
        }
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            // 에뮬레이터는 안내 중에도 isRouting=false를 줄 때가 있어 여기서는 지우지 않는다(취소는 onRouteCancelled로 받는다)
            Log.i(TAG, "routeState isRouting=${routeStateInfo.isRouting}")
        }
        override fun onDestinationChanged() {
            // 목적지 설정/해제 → 최신 정보 재요청
            main.post { requestDestination() }
        }
        override fun onRouteEnded() { Log.i(TAG, "onRouteEnded") }
        override fun onRouteCancelled() {
            Log.i(TAG, "onRouteCancelled")
            main.post { clearRoute() }
        }
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            val p = NaviGateway.Place("", currentLocationInfo.address, currentLocationInfo.latitude, currentLocationInfo.longitude)
            main.post { lastCurrentLocation = p; listener?.onCurrentLocation(p) }
        }
        override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) {
            val r = recentDestinationInfoList.firstOrNull() ?: return
            val p = NaviGateway.Place(r.name, r.address, r.latitude, r.longitude)
            main.post { lastRecentDestination = p }
        }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            val p = NaviGateway.Place(destinationArrivedInfo.name, destinationArrivedInfo.address)
            main.post { listener?.onArrived(p, destinationArrivedInfo.distance, destinationArrivedInfo.duration) }
        }
        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "navi error: $errorCode")
        }
    }

    private fun clearRoute() {
        if (lastDestination == null && lastRouteName == null) return
        lastDestination = null; lastRouteName = null; lastRouteKm = null
        listener?.onDestination(null)
    }

    override fun start(listener: NaviGateway.Listener) {
        this.listener = listener
        if (started) return
        started = true
        try {
            navi.initialize(
                onConnected = {
                    main.post {
                        connected = true
                        safe { navi.addListener(events) }
                        requestDestination(); requestCurrentLocation(); requestRecentDestination()
                        safe { navi.getRouteStateInfo() }
                    }
                },
                onFailed = { t -> Log.w(TAG, "navi connect failed: ${t.message}"); main.post { connected = false } }
            )
        } catch (e: Exception) {
            Log.w(TAG, "navi init exception: ${e.message}")
        }
    }

    override fun requestDestination() { if (connected) safe { navi.getDestinationInfo() } }
    override fun requestCurrentLocation() { if (connected) safe { navi.getCurrentLocationInfo() } }
    override fun requestRecentDestination() { if (connected) safe { navi.getRecentDestinationInfo() } }

    override fun stop() {
        if (!started) return
        safe { navi.removeListener(events) }
        safe { navi.release() }
        started = false; connected = false; listener = null
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (e: Exception) { Log.w(TAG, "navi call failed: ${e.message}") }
    }
}
