package ai.kilopace.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 내비 화면이 앞에 있어도 차량 신호와 목적지 이벤트를 받아 말할 수 있도록 프로세스를 살려 두는 포그라운드 서비스.
 * 서비스 자체는 아무 일도 하지 않는다(신호 처리는 Application 스코프의 TripController가 한다).
 * 이 서비스가 없으면 앱이 뒤로 간 동안 시스템이 프로세스를 멈춰(freeze) 이벤트가 앱을 다시 열 때까지 밀린다.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "주행거리 약정 감시", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("목적지를 넣으면 약정거리를 넘는지 알려 드립니다")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "keepalive"
        private const val TAG = "KiloPace/Keep"

        /** 앱이 화면에 보이는 동안 호출해야 한다(백그라운드에서는 시작이 막힌다). */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, KeepAliveService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "start failed: ${e.message}")
            }
        }
    }
}
