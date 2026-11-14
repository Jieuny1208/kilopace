package ai.kilopace.app

import ai.kilopace.app.data.PlanStore
import ai.kilopace.app.data.Settings
import ai.kilopace.app.data.TripStore
import ai.kilopace.app.signal.PleosLocationGateway
import ai.kilopace.app.signal.PleosNaviGateway
import ai.kilopace.app.signal.PleosVehicleGateway
import ai.kilopace.app.trip.TripController
import ai.kilopace.app.ux.UxRestrictionsMonitor
import ai.kilopace.app.voice.VoiceInput
import ai.kilopace.app.voice.VoicePrompter
import android.app.Application

class KiloPaceApp : Application() {

    lateinit var settings: Settings; private set
    lateinit var store: TripStore; private set
    lateinit var plans: PlanStore; private set
    lateinit var ux: UxRestrictionsMonitor; private set
    lateinit var controller: TripController; private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        store = TripStore(this)
        plans = PlanStore(this)
        ux = UxRestrictionsMonitor(this)
        controller = TripController(
            vehicle = PleosVehicleGateway(this),
            navi = PleosNaviGateway(this),
            location = PleosLocationGateway(this),
            voice = VoicePrompter(this),
            voiceInput = VoiceInput(this),
            ux = ux,
            store = store,
            plans = plans,
            settings = settings,
        )
        controller.start()
    }

    override fun onTerminate() {
        controller.stop()
        super.onTerminate()
    }
}
