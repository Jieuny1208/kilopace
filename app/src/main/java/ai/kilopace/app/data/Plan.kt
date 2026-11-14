package ai.kilopace.app.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong

/** 계기판 누적거리로 정산되는 약정의 종류 */
enum class PlanType(val label: String) {
    LEASE("리스·장기렌트"),
    INSURANCE("보험 마일리지");

    companion object {
        fun of(name: String?): PlanType = entries.firstOrNull { it.name == name } ?: LEASE
    }
}

/** 보험 마일리지 특약 구간: 연간 주행거리가 maxKm 이하면 보험료의 discountPct %를 돌려받는다. */
data class Tier(val maxKm: Double, val discountPct: Double)

/**
 * 약정 1건.
 *  - LEASE: 기간 안에 limitKm까지 달릴 수 있고, 넘으면 km당 feePerKm 원.
 *  - INSURANCE: 기간 동안 달린 거리가 속한 구간(tiers)의 할인율만큼 premiumWon에서 환급.
 */
data class Plan(
    val type: PlanType,
    val startAt: Long,
    val endAt: Long,
    val startOdoKm: Double,
    val limitKm: Double = 0.0,
    val feePerKm: Double = 0.0,
    val premiumWon: Double = 0.0,
    val tiers: List<Tier> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.name); put("startAt", startAt); put("endAt", endAt); put("startOdoKm", startOdoKm)
        put("limitKm", limitKm); put("feePerKm", feePerKm); put("premiumWon", premiumWon)
        put("tiers", formatTiers(tiers))
    }

    companion object {
        fun fromJson(o: JSONObject): Plan = Plan(
            type = PlanType.of(o.optString("type")),
            startAt = o.getLong("startAt"), endAt = o.getLong("endAt"),
            startOdoKm = o.optDouble("startOdoKm", 0.0),
            limitKm = o.optDouble("limitKm", 0.0), feePerKm = o.optDouble("feePerKm", 0.0),
            premiumWon = o.optDouble("premiumWon", 0.0),
            tiers = parseTiers(o.optString("tiers")),
        )

        /** "3000:32, 5000:25" → 구간 목록(거리 오름차순). 형식이 틀린 항목은 버린다. */
        fun parseTiers(text: String?): List<Tier> {
            // "5,000:25"처럼 천 단위 쉼표를 쓴 경우: 숫자만 있는 조각은 다음 조각 앞에 붙인다
            val parts = mutableListOf<String>()
            var prefix = ""
            for (raw in text.orEmpty().split(',', ';', '\n')) {
                val p = raw.trim()
                when {
                    p.isNotEmpty() && p.all { it.isDigit() } -> prefix += p
                    else -> { parts += prefix + p; prefix = "" }
                }
            }
            return parts.mapNotNull { part ->
                val kv = part.split(':', '=')
                if (kv.size != 2) return@mapNotNull null
                val km = kv[0].trim().replace(",", "").toDoubleOrNull() ?: return@mapNotNull null
                val pct = kv[1].trim().removeSuffix("%").toDoubleOrNull() ?: return@mapNotNull null
                if (km <= 0 || pct < 0) null else Tier(km, pct)
            }.sortedBy { it.maxKm }
        }

        fun formatTiers(tiers: List<Tier>): String =
            tiers.joinToString(", ") { "${it.maxKm.roundToLong()}:${Fmt.num(it.discountPct)}" }
    }
}

enum class PaceStatus { OK, TIGHT, OVER }

/** 지금 계기판 값으로 본 약정 상태 */
data class Pace(
    val plan: Plan,
    val usedKm: Double,
    val totalDays: Int,
    val elapsedDays: Double,
    val remainingDays: Int,
    /** 지금 페이스로 만기까지 달렸을 때 예상 사용 거리. 시작 직후(하루 미만)는 null */
    val projectedKm: Double?,
    /** 지켜야 할 한도. LEASE=약정거리, INSURANCE=지금 속한 구간의 상한. 마지막 구간도 넘었으면 null */
    val limitKm: Double?,
    /** 지금 속한 보험 구간 (LEASE·구간 밖이면 null) */
    val tier: Tier?,
    /** 지금 기준 금액. LEASE=초과 운행료(원, 0 이상), INSURANCE=예상 환급액(원) */
    val amountWon: Double,
    /** 지금 페이스 기준 만기 금액. 의미는 amountWon과 같다 */
    val projectedAmountWon: Double?,
) {
    /** 한도까지 남은 거리. 음수면 이미 넘음. 한도가 없으면 null */
    val remainingKm: Double? get() = limitKm?.let { it - usedKm }

    /** 한도를 지키려면 하루 평균 몇 km 이하여야 하는가 */
    val dailyAllowanceKm: Double?
        get() = remainingKm?.let { max(0.0, it) / max(1, remainingDays) }

    val status: PaceStatus
        get() {
            val lim = limitKm ?: return PaceStatus.OVER
            if (usedKm > lim) return PaceStatus.OVER
            val p = projectedKm ?: return PaceStatus.OK
            return if (p > lim) PaceStatus.TIGHT else PaceStatus.OK
        }
}

/** 추가로 extraKm을 달렸을 때의 변화 */
data class Impact(
    val before: Pace,
    val after: Pace,
    val extraKm: Double,
) {
    /** 이번 이동으로 한도(약정거리·구간 상한)를 새로 넘는가 */
    val crosses: Boolean
        get() = when (before.plan.type) {
            PlanType.LEASE -> before.status != PaceStatus.OVER && after.status == PaceStatus.OVER
            PlanType.INSURANCE -> before.tier != after.tier
        }

    /** 이번 이동 때문에 늘어나는 부담(원). LEASE=초과 운행료 증가분, INSURANCE=환급액 감소분 */
    val costWon: Double
        get() = when (before.plan.type) {
            PlanType.LEASE -> max(0.0, after.amountWon - before.amountWon)
            PlanType.INSURANCE -> max(0.0, before.amountWon - after.amountWon)
        }

    /** 다녀온 뒤 한도를 넘는 거리(km). 넘지 않으면 0 */
    val overKm: Double get() = after.remainingKm?.let { max(0.0, -it) } ?: 0.0
}

/** 약정 계산기 (순수 Kotlin, Android 의존 없음 → JVM 단위 테스트) */
object PaceCalc {
    private const val DAY = 86_400_000.0

    fun pace(plan: Plan, odoKm: Double, now: Long): Pace {
        val used = max(0.0, odoKm - plan.startOdoKm)
        val totalDays = max(1, ceil((plan.endAt - plan.startAt) / DAY).toInt())
        val elapsed = ((now - plan.startAt) / DAY).coerceIn(0.0, totalDays.toDouble())
        val remainingDays = max(0, ceil((plan.endAt - now) / DAY).toInt())
        val projected = if (elapsed >= 1.0) used / elapsed * totalDays else null
        return when (plan.type) {
            PlanType.LEASE -> Pace(
                plan, used, totalDays, elapsed, remainingDays, projected,
                limitKm = plan.limitKm, tier = null,
                amountWon = leaseFee(plan, used),
                projectedAmountWon = projected?.let { leaseFee(plan, it) },
            )
            PlanType.INSURANCE -> {
                val tier = tierFor(plan, used)
                Pace(
                    plan, used, totalDays, elapsed, remainingDays, projected,
                    limitKm = tier?.maxKm, tier = tier,
                    amountWon = refund(plan, tier),
                    projectedAmountWon = projected?.let { refund(plan, tierFor(plan, it)) },
                )
            }
        }
    }

    fun impact(plan: Plan, odoKm: Double, extraKm: Double, now: Long): Impact =
        Impact(pace(plan, odoKm, now), pace(plan, odoKm + max(0.0, extraKm), now), max(0.0, extraKm))

    /** 주행거리가 속하는 구간. 가장 넓은 구간도 넘으면 null(환급 없음) */
    fun tierFor(plan: Plan, usedKm: Double): Tier? = plan.tiers.firstOrNull { usedKm <= it.maxKm }

    private fun refund(plan: Plan, tier: Tier?): Double = if (tier == null) 0.0 else plan.premiumWon * tier.discountPct / 100.0

    private fun leaseFee(plan: Plan, usedKm: Double): Double = max(0.0, usedKm - plan.limitKm) * plan.feePerKm
}

/** 숫자·날짜 표시 */
object Fmt {
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)

    /** 1234.4 → "1,234" */
    fun km(v: Double?): String = if (v == null) "—" else String.format(Locale.US, "%,d", v.roundToLong())
    fun won(v: Double?): String = if (v == null) "—" else String.format(Locale.US, "%,d", v.roundToLong())
    /** 소수점이 0이면 떼고 보여 준다: 25.0 → "25", 12.5 → "12.5" */
    fun num(v: Double): String = if (v == v.roundToLong().toDouble()) v.roundToLong().toString() else v.toString()
    fun date(millis: Long): String = dateFmt.format(Date(millis))
    /** "2026-11-30" → 그날 0시(현지). 형식이 틀리면 null */
    fun parseDate(text: String): Long? = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).apply { isLenient = false }.parse(text.trim())?.time
    }.getOrNull()
}
