package ai.kilopace.app.ui

import ai.kilopace.app.KiloPaceApp
import ai.kilopace.app.R
import ai.kilopace.app.data.Fmt
import ai.kilopace.app.trip.TripController
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment

/** 화면 갱신 요청을 받는 프래그먼트 */
interface Refreshable {
    fun refresh()
    fun onVoiceText(text: String?, final: Boolean) {}
}

class MainActivity : AppCompatActivity(), TripController.UiListener {

    val app: KiloPaceApp get() = application as KiloPaceApp
    val controller: TripController get() = app.controller

    private lateinit var strip: TextView
    private var current: Fragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        strip = findViewById(R.id.txtStatusStrip)
        findViewById<Button>(R.id.tabDriving).setOnClickListener { showHome() }
        findViewById<Button>(R.id.tabLedger).setOnClickListener { show(LedgerFragment(), "ledger") }
        findViewById<Button>(R.id.tabSettings).setOnClickListener { show(PlansFragment(), "plans") }
        if (savedInstanceState == null) showHome() else current = supportFragmentManager.findFragmentById(R.id.container)
    }

    override fun onStart() {
        super.onStart()
        controller.attach(this)
        ai.kilopace.app.KeepAliveService.start(this)   // 화면이 보이는 동안에만 시작할 수 있다
    }
    override fun onStop() { controller.detach(this); super.onStop() }

    fun showHome() = show(HomeFragment(), "home")

    private fun show(f: Fragment, tag: String) {
        if (supportFragmentManager.findFragmentByTag(tag) != null && current?.tag == tag) return
        if (supportFragmentManager.isStateSaved) return
        supportFragmentManager.beginTransaction().replace(R.id.container, f, tag).commitNow()
        current = f
        (f as? Refreshable)?.refresh()
    }

    // ---- TripController.UiListener (메인 스레드) ----

    override fun onStateChanged() {
        val door = if (controller.doorOpen) "열림" else "닫힘"
        val parked = if (controller.parked) "정차" else "주행"
        val navi = if (controller.naviConnected) "navi✓" else "navi✗"
        strip.text = "odo ${Fmt.km(controller.odometerKm)} km · 도어 $door · $parked · $navi"
        (current as? Refreshable)?.refresh()
    }

    override fun onVoiceText(text: String?, final: Boolean) {
        (current as? Refreshable)?.onVoiceText(text, final)
    }

    override fun onMessage(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
