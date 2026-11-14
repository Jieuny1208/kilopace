package ai.kilopace.app.ui

import ai.kilopace.app.R
import ai.kilopace.app.data.Fmt
import ai.kilopace.app.data.Plan
import ai.kilopace.app.data.PlanType
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlin.math.max

/** 약정 등록·수정. 정차(P단) 중에만 열린다. */
class PlansFragment : Fragment(R.layout.fragment_plans), Refreshable {

    private val act get() = requireActivity() as MainActivity
    private val uxListener: (Boolean) -> Unit = { view?.findViewById<View>(R.id.txtLocked)?.visibility = if (it) View.VISIBLE else View.GONE }
    private var stall = 20

    private fun edit(id: Int): EditText = requireView().findViewById(id)
    private fun text(id: Int): String = edit(id).text.toString().trim()
    private fun number(id: Int): Double? = text(id).replace(",", "").toDoubleOrNull()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val app = act.app
        val s = app.settings

        app.plans.get(PlanType.LEASE)?.let { p ->
            edit(R.id.editLeaseStart).setText(Fmt.date(p.startAt)); edit(R.id.editLeaseEnd).setText(Fmt.date(p.endAt))
            edit(R.id.editLeaseStartOdo).setText(Fmt.num(p.startOdoKm))
            edit(R.id.editLeaseLimit).setText(Fmt.num(p.limitKm)); edit(R.id.editLeaseFee).setText(Fmt.num(p.feePerKm))
        }
        app.plans.get(PlanType.INSURANCE)?.let { p ->
            edit(R.id.editInsStart).setText(Fmt.date(p.startAt)); edit(R.id.editInsEnd).setText(Fmt.date(p.endAt))
            edit(R.id.editInsStartOdo).setText(Fmt.num(p.startOdoKm))
            edit(R.id.editInsPremium).setText(Fmt.num(p.premiumWon)); edit(R.id.editInsTiers).setText(Plan.formatTiers(p.tiers))
        }

        // 시작 계기판을 차량 신호에서 그대로 가져온다
        view.findViewById<Button>(R.id.btnLeaseOdoNow).setOnClickListener { fillOdo(R.id.editLeaseStartOdo) }
        view.findViewById<Button>(R.id.btnInsOdoNow).setOnClickListener { fillOdo(R.id.editInsStartOdo) }

        stall = s.stallSeconds
        val txtStall = view.findViewById<TextView>(R.id.txtStall)
        fun showStall() { txtStall.text = "${stall}초" }
        showStall()
        view.findViewById<Button>(R.id.btnStallMinus).setOnClickListener { stall = (stall - 10).coerceAtLeast(5); showStall() }
        view.findViewById<Button>(R.id.btnStallPlus).setOnClickListener { stall = (stall + 10).coerceAtMost(600); showStall() }

        val toggleVoice = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleVoice)
        toggleVoice.check(if (s.voiceEnabled) R.id.btnVoiceOn else R.id.btnVoiceOff)

        view.findViewById<Button>(R.id.btnDemo).setOnClickListener { fillExample() }
        view.findViewById<Button>(R.id.btnSave).setOnClickListener {
            val err = save()
            s.stallSeconds = stall
            s.voiceEnabled = toggleVoice.checkedButtonId == R.id.btnVoiceOn
            Toast.makeText(requireContext(), err ?: "저장했습니다", Toast.LENGTH_LONG).show()
            act.onStateChanged()
        }
        app.ux.addListener(uxListener)
        refresh()
    }

    override fun onDestroyView() { act.app.ux.removeListener(uxListener); super.onDestroyView() }

    private fun fillOdo(id: Int) {
        val odo = act.controller.odometerKm
        if (odo == null) Toast.makeText(requireContext(), "계기판 값을 아직 받지 못했습니다", Toast.LENGTH_SHORT).show()
        else edit(id).setText(Fmt.num(odo))
    }

    /**
     * 심사·시연용 예시 값. 지금 계기판을 기준으로 "만기 15일 전, 약정거리까지 440km" 상황을 만든다.
     * 금액과 구간은 실제 계약이 아니라 예시다.
     */
    private fun fillExample() {
        val odo = act.controller.odometerKm ?: 20_000.0
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        edit(R.id.editLeaseStart).setText(Fmt.date(now - 715 * day)); edit(R.id.editLeaseEnd).setText(Fmt.date(now + 15 * day))
        edit(R.id.editLeaseStartOdo).setText(Fmt.num(max(0.0, odo - 19_560.0)))
        edit(R.id.editLeaseLimit).setText("20000"); edit(R.id.editLeaseFee).setText("100")
        edit(R.id.editInsStart).setText(Fmt.date(now - 259 * day)); edit(R.id.editInsEnd).setText(Fmt.date(now + 106 * day))
        edit(R.id.editInsStartOdo).setText(Fmt.num(max(0.0, odo - 6_360.0)))
        edit(R.id.editInsPremium).setText("680000"); edit(R.id.editInsTiers).setText("3000:32, 5000:25, 7000:20, 10000:15, 12000:5")
        Toast.makeText(requireContext(), "예시 값을 채웠습니다. 저장을 눌러 주세요.", Toast.LENGTH_SHORT).show()
    }

    /** 저장. 문제가 있으면 안내 문장을 돌려준다(null = 정상). */
    private fun save(): String? {
        val plans = act.app.plans
        val errors = mutableListOf<String>()

        if (text(R.id.editLeaseStart).isBlank()) plans.remove(PlanType.LEASE) else {
            val start = Fmt.parseDate(text(R.id.editLeaseStart)); val end = Fmt.parseDate(text(R.id.editLeaseEnd))
            val odo = number(R.id.editLeaseStartOdo); val limit = number(R.id.editLeaseLimit); val fee = number(R.id.editLeaseFee) ?: 0.0
            if (start == null || end == null || end <= start) errors += "리스·장기렌트: 날짜를 2026-11-30 형식으로, 만기가 시작보다 뒤가 되게 넣어 주세요"
            else if (odo == null || limit == null || limit <= 0) errors += "리스·장기렌트: 시작 계기판과 약정거리를 넣어 주세요"
            else plans.put(Plan(PlanType.LEASE, start, end, odo, limitKm = limit, feePerKm = fee))
        }

        if (text(R.id.editInsStart).isBlank()) plans.remove(PlanType.INSURANCE) else {
            val start = Fmt.parseDate(text(R.id.editInsStart)); val end = Fmt.parseDate(text(R.id.editInsEnd))
            val odo = number(R.id.editInsStartOdo); val premium = number(R.id.editInsPremium) ?: 0.0
            val tiers = Plan.parseTiers(text(R.id.editInsTiers))
            if (start == null || end == null || end <= start) errors += "보험: 날짜를 2027-02-28 형식으로, 만기가 시작보다 뒤가 되게 넣어 주세요"
            else if (odo == null || tiers.isEmpty()) errors += "보험: 시작 계기판과 구간(예: 5000:25, 7000:20)을 넣어 주세요"
            else plans.put(Plan(PlanType.INSURANCE, start, end, odo, premiumWon = premium, tiers = tiers))
        }
        return if (errors.isEmpty()) null else errors.joinToString("\n")
    }

    override fun refresh() {
        val c = act.controller
        view?.findViewById<TextView>(R.id.txtEnv)?.text = buildString {
            append("device ${Build.MANUFACTURER}/${Build.MODEL} · API ${Build.VERSION.SDK_INT} · ")
            append("Odometer ${when (c.odometerSupported) { true -> "READ"; false -> "미지원"; null -> "확인 중" }} ${Fmt.km(c.odometerKm)} km · ")
            append("CarUxRestrictions ${if (act.app.ux.available) "연동" else "미제공(정차로 간주)"} · ")
            append("Navi ${if (c.naviConnected) "연결" else "미연결"}\n")
            append("약정·운행 기록은 차량 로컬(앱 전용 저장소)에만 저장합니다 · 서버 전송 없음")
        }
    }
}
