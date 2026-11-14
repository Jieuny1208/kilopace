package ai.kilopace.app.signal

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/** FusedLocation SDK 1.1.3. 운행 시작·종료 시 마지막 위치 1회만 읽는다(증빙 좌표). */
class PleosLocationGateway(context: Context) : LocationGateway {

    companion object { private const val TAG = "KiloPace/Location" }

    private val fused = FusedLocation(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    override var supported: Boolean = false; private set

    override fun start() {
        if (started) return
        try {
            supported = fused.isSupported()
            if (supported) fused.initialize()
            started = true
        } catch (e: Exception) {
            Log.w(TAG, "init failed: ${e.message}"); supported = false
        }
    }

    override fun lastKnown(onResult: (LocationGateway.Fix?) -> Unit) {
        if (!started || !supported) { onResult(null); return }
        try {
            fused.getLastKnownLocation(object : FusedLocationListener {
                override fun onChanged(location: LocationData?) {
                    val fix = location?.let { LocationGateway.Fix(it.latitude, it.longitude, it.timestampMillis) }
                    main.post { onResult(fix) }
                }
                override fun onError(exception: Exception) {
                    Log.w(TAG, "lastKnown error: ${exception.message}")
                    main.post { onResult(null) }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "lastKnown exception: ${e.message}"); onResult(null)
        }
    }

    override fun stop() {
        if (!started) return
        try { fused.release() } catch (_: Exception) {}
        started = false
    }
}
