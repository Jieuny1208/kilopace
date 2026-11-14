package ai.kilopace.app.ui

import ai.kilopace.app.R
import ai.kilopace.app.data.Fmt
import ai.kilopace.app.data.Pace
import ai.kilopace.app.data.PaceStatus
import ai.kilopace.app.data.PlanType
import ai.kilopace.app.data.Speech
import ai.kilopace.app.data.VoiceQuery
import ai.kilopace.app.trip.TripStateMachine
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

/** 주행 중에도 보이는 화면. 약정별 남은 거리(큰 숫자)와 목적지 영향. 입력은 큰 버튼뿐이다. */
class HomeFragment : Fragment(R.layout.fragment_home), Refreshable {

    private val act get() = requireActivity() as MainActivity

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val c = act.controller
        view.findViewById<Button>(R.id.btnRemain).setOnClickListener { c.answer(VoiceQuery.Intent.REMAINING) }
        view.findViewById<Button>(R.id.btnPace).setOnClickListener { c.answer(VoiceQuery.Intent.PROJECTION) }
        view.findViewById<Button>(R.id.btnMic).setOnClickListener {
            view.findViewById<TextView>(R.id.txtVoice).text =
                "🎤 듣는 중… “얼마 남았어?” 또는 “이 페이스면?”"
            c.askByVoice()
        }
        view.findViewById<Button>(R.id.btnStart).setOnClickListener { c.startTrip() }
        view.findViewById<Button>(R.id.btnArrive).setOnClickListener { c.requestEnd() }
        refresh()
    }

    override fun refresh() {
        val v = view ?: return
        val c = act.controller
        val open = c.state != TripStateMachine.State.IDLE
        val trip = c.currentTrip

        v.findViewById<TextView>(R.id.txtState).text =
            (if (open) "운행 중" else "대기 중") + " · 계기판 ${Fmt.km(c.odometerKm)} km"

        val paces = c.paces()
        bindPlan(v, paces.getOrNull(0), R.id.txtPlan1Title, R.id.txtPlan1Big, R.id.txtPlan1Detail)
        bindPlan(v, paces.getOrNull(1), R.id.txtPlan2Title, R.id.txtPlan2Big, R.id.txtPlan2Detail)
        v.findViewById<TextView>(R.id.txtNoPlan).apply {
            visibility = if (paces.isEmpty()) View.VISIBLE else View.GONE
            text = if (c.odometerKm == null) "계기판 누적거리를 기다리는 중입니다…"
            else "등록된 약정이 없습니다.\n정차 중에 약정 탭에서 리스·장기렌트 약정거리나 보험 마일리지 구간을 등록해 주세요."
        }

        v.findViewById<TextView>(R.id.txtTrip).text =
            if (open) "이번 운행 ${Fmt.km(c.liveDistanceKm)} km · 출발 계기판 ${Fmt.km(trip?.odoStartKm)} km" else ""

        // 목적지 영향
        val a = c.advice
        val txtDest = v.findViewById<TextView>(R.id.txtDest)
        val txtAdvice = v.findViewById<TextView>(R.id.txtAdvice)
        if (a == null) {
            txtDest.text = "내비에 목적지를 넣으면 여기에 표시됩니다."
            txtAdvice.text = ""
        } else {
            val name = a.dest.name.ifBlank { a.dest.address }
            val est = a.estimate
            txtDest.text = if (est == null) name else "$name · 왕복 약 ${Fmt.km(est.roundTripKm)} km (${est.source.label})"
            txtAdvice.text = when {
                est == null -> "왕복 거리를 추정할 근거가 없습니다. 한 번 다녀오면 그 거리로 알려 드립니다."
                a.impacts.isEmpty() -> "등록된 약정이 없습니다."
                else -> a.impacts.joinToString("\n") { Speech.impactLine(it) }
            }
            txtAdvice.setTextColor(ContextCompat.getColor(requireContext(), if (a.crosses) R.color.warn else R.color.text))
        }

        v.findViewById<TextView>(R.id.txtSpoken).text = if (c.lastSpoken.isBlank()) "" else "🔊 ${c.lastSpoken}"

        v.findViewById<Button>(R.id.btnStart).visibility = if (open) View.GONE else View.VISIBLE
        v.findViewById<Button>(R.id.btnArrive).visibility = if (open) View.VISIBLE else View.GONE

        v.findViewById<TextView>(R.id.txtSignals).text = buildString {
            append("Odometer ").append(when (c.odometerSupported) { true -> "READ ✓"; false -> "미지원"; null -> "확인 중" })
            append(" · 도어 ").append(if (c.doorOpen) "열림" else "닫힘")
            append(" · ").append(if (c.parked) "정차" else "주행 중(입력 잠금)")
            append(" · 내비 ").append(if (c.naviConnected) "연결" else "미연결")
        }
        v.findViewById<TextView>(R.id.txtError).text = c.lastVehicleError?.let { "최근 SDK 오류: $it" } ?: ""
    }

    private fun bindPlan(v: View, p: Pace?, titleId: Int, bigId: Int, detailId: Int) {
        val title = v.findViewById<TextView>(titleId)
        val big = v.findViewById<TextView>(bigId)
        val detail = v.findViewById<TextView>(detailId)
        if (p == null) { title.visibility = View.GONE; big.visibility = View.GONE; detail.visibility = View.GONE; return }
        title.visibility = View.VISIBLE; big.visibility = View.VISIBLE; detail.visibility = View.VISIBLE

        val pl = p.plan
        val rem = p.remainingKm
        title.text = when (pl.type) {
            PlanType.LEASE -> "${pl.type.label} · 약정 ${Fmt.km(pl.limitKm)} km 중 ${Fmt.km(p.usedKm)} km 사용"
            PlanType.INSURANCE -> "${pl.type.label} · " +
                (p.tier?.let { "${Fmt.km(it.maxKm)} km 이하 구간 (${Fmt.num(it.discountPct)}% 환급)" } ?: "할인 구간 밖") +
                " · ${Fmt.km(p.usedKm)} km 사용"
        }
        big.text = when {
            rem == null -> "구간 밖"
            rem < 0 -> "${Fmt.km(-rem)} km 초과"
            else -> "${Fmt.km(rem)} km 남음"
        }
        big.setTextColor(ContextCompat.getColor(requireContext(), when (p.status) {
            PaceStatus.OK -> R.color.accent
            PaceStatus.TIGHT -> R.color.warn
            PaceStatus.OVER -> R.color.danger
        }))
        detail.text = buildString {
            append("만기까지 ").append(p.remainingDays).append("일")
            p.dailyAllowanceKm?.let { if (rem != null && rem >= 0) append(" · 하루 ").append(Fmt.km(it)).append(" km 이하면 지킴") }
            append('\n')
            val proj = p.projectedKm
            if (proj == null) append("기록이 하루가 안 돼 만기 예상은 아직 없습니다")
            else when (pl.type) {
                PlanType.LEASE -> {
                    val over = proj - pl.limitKm
                    if (over > 0) append("이 페이스면 만기에 ").append(Fmt.km(over)).append(" km 초과 · 약 ").append(Fmt.won(p.projectedAmountWon)).append("원")
                    else append("이 페이스면 만기에 ").append(Fmt.km(-over)).append(" km 남음")
                }
                PlanType.INSURANCE ->
                    append("지금 구간 환급 약 ").append(Fmt.won(p.amountWon)).append("원 · 이 페이스면 만기 ")
                        .append(Fmt.km(proj)).append(" km, 약 ").append(Fmt.won(p.projectedAmountWon)).append("원")
            }
        }
    }

    override fun onVoiceText(text: String?, final: Boolean) {
        val tv = view?.findViewById<TextView>(R.id.txtVoice) ?: return
        tv.text = when {
            !final && !text.isNullOrBlank() -> "🎤 $text"
            final && text.isNullOrBlank() -> "🎤 음성 입력이 없어 남은 거리로 답했습니다"
            else -> "🎤 “$text”"
        }
    }

    fun showMessage(text: String) {
        view?.findViewById<TextView>(R.id.txtVoice)?.text = text
    }
}
