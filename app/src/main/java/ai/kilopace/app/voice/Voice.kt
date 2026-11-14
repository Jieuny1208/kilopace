package ai.kilopace.app.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode

/**
 * TTS(ON_DEVICE, ko-KR): 목적지 경고·운행 종료 한 줄·질문 대답.
 * ON_DEVICE 모드는 Pleos Playground 앱 등록(clientID)이 필요 없다(공식 문서).
 */
class VoicePrompter(context: Context) {
    companion object { private const val TAG = "KiloPace/TTS" }

    private val tts = TextToSpeech(context.applicationContext, TtsMode.ON_DEVICE)
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    var onDone: (() -> Unit)? = null

    private val events = object : EventListener {
        override fun onReady() { ready = true }
        override fun onStart() {}
        override fun onUpdatedRms(rms: Double) {}
        override fun onDone() { main.post { onDone?.invoke() } }
        override fun onStop() {}
        override fun onError(errorType: TtsError) { Log.w(TAG, "tts error: $errorType"); main.post { onDone?.invoke() } }
    }

    fun start() {
        try {
            tts.initialize()
            tts.addEventListener(events)
            tts.setLocale(Locale.KOREA)
            ready = true
        } catch (e: Exception) { Log.w(TAG, "init failed: ${e.message}") }
    }

    fun speak(text: String) {
        if (!ready) { Log.i(TAG, "(tts not ready) $text"); main.post { onDone?.invoke() }; return }
        try { tts.speak(text) } catch (e: Exception) { Log.w(TAG, "speak failed: ${e.message}"); main.post { onDone?.invoke() } }
    }

    fun stop() { try { tts.stop() } catch (_: Exception) {} }

    // ---- 문장 대기열: 한 문장씩 끝까지 말하고 다음 문장으로 넘어간다 ----

    private val queue = ArrayDeque<String>()
    private var speaking = false
    private val watchdog = Runnable { next() }

    /**
     * 안내를 문장 단위로 줄 세워 말한다.
     * interrupt=false면 지금 말하는 안내가 끝난 뒤에 이어서 말하고(차가 먼저 거는 말끼리는 서로 끊지 않는다),
     * interrupt=true면 운전자가 방금 누른 질문이므로 앞의 안내를 멈추고 바로 답한다.
     */
    fun say(sentences: List<String>, interrupt: Boolean) {
        if (sentences.isEmpty()) return
        if (interrupt) { queue.clear(); main.removeCallbacks(watchdog); if (speaking) stop(); speaking = false }
        queue.addAll(sentences)
        if (!speaking) next()
    }

    private fun next() {
        main.removeCallbacks(watchdog)
        val s = queue.removeFirstOrNull()
        if (s == null) { speaking = false; onDone = null; return }
        speaking = true
        onDone = { next() }
        // 완료 콜백이 오지 않는 경우에 대비해 문장 길이에 비례한 시간 뒤에는 다음 문장으로 넘어간다
        main.postDelayed(watchdog, 4_000L + s.length * 350L)
        speak(s)
    }

    /** 대기 중인 안내까지 모두 멈춘다 */
    fun silence() { queue.clear(); main.removeCallbacks(watchdog); speaking = false; onDone = null; stop() }

    fun release() {
        try { tts.removeEventListener(events); tts.release() } catch (_: Exception) {}
    }
}

/**
 * STT(ON_DEVICE, ko-KR) 한마디 입력. 에뮬레이터는 마이크 입력이 없어 보통 onError/타임아웃으로 끝난다(SoftRide 관측).
 * 그래서 UI는 항상 버튼을 함께 보여 주고, STT는 보조 입력이다.
 */
class VoiceInput(context: Context) {
    companion object { private const val TAG = "KiloPace/STT" }

    private val stt = SpeechToText(context.applicationContext, SttMode.ON_DEVICE)
    private val main = Handler(Looper.getMainLooper())
    private var listening = false
    private var onResult: ((String) -> Unit)? = null
    private var onFinished: ((String?) -> Unit)? = null
    private var lastPartial = ""

    private val results = object : ResultListener {
        override fun onReady() {}
        override fun onStartedRecognition() {}
        override fun onUpdated(stt: String, completed: Boolean) {
            lastPartial = stt
            main.post { onResult?.invoke(stt) }
            if (completed) main.post { finish(stt) }
        }
        override fun onUpdatedEpdData(on: Long, off: Long) {}
        override fun onUpdatedRms(rms: Double) {}
        override fun onEndedRecognition() { main.post { finish(lastPartial.ifBlank { null }) } }
        override fun onError(errorType: SttError) { Log.w(TAG, "stt error: $errorType"); main.post { finish(null) } }
    }

    fun start() {
        try {
            stt.initialize()
            stt.addListener(results)
            stt.setLocale(Locale.KOREA)
        } catch (e: Exception) { Log.w(TAG, "init failed: ${e.message}") }
    }

    /** 인식 시작. partial은 중간 결과, finished는 최종(null=실패·무응답). 8초 타임아웃. */
    fun listen(partial: (String) -> Unit, finished: (String?) -> Unit) {
        if (listening) return
        listening = true; lastPartial = ""
        onResult = partial; onFinished = finished
        try { stt.request() } catch (e: Exception) { Log.w(TAG, "request failed: ${e.message}"); finish(null); return }
        main.postDelayed({ if (listening) { try { stt.stop() } catch (_: Exception) {}; finish(lastPartial.ifBlank { null }) } }, 8_000L)
    }

    private fun finish(text: String?) {
        if (!listening) return
        listening = false
        val cb = onFinished
        onFinished = null; onResult = null
        cb?.invoke(text)
    }

    fun cancel() { if (listening) { try { stt.stop() } catch (_: Exception) {}; finish(null) } }

    fun release() { try { stt.removeListener(results); stt.release() } catch (_: Exception) {} }
}

