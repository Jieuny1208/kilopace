package ai.kilopace.app.data

import kotlin.math.max

/** 왕복 거리 추정 결과 */
data class Estimate(val roundTripKm: Double, val source: Source) {
    enum class Source(val label: String) {
        ROUTE("내비 경로 거리"),
        HISTORY("지난 운행 평균"),
        STRAIGHT("직선거리 기준 추정"),
    }
}

/** 목적지 왕복 거리 추정 (순수 Kotlin) */
object TripEstimator {
    /** 직선거리를 도로 거리로 바꿀 때 곱하는 값. 경로 탐색 결과가 아니라 어림값이다. */
    const val ROAD_FACTOR = 1.3

    /**
     * 1) 내비가 계산한 편도 경로 거리가 있으면 × 2
     * 2) 같은 이름의 목적지로 달린 기록이 있으면 그 평균 편도 거리 × 2
     * 3) 좌표가 있으면 직선거리 × ROAD_FACTOR × 2 (어림값)
     * 4) 모두 없으면 null
     */
    fun roundTrip(
        destName: String, destLat: Double?, destLng: Double?,
        curLat: Double?, curLng: Double?, history: List<TripRecord>,
        routeOneWayKm: Double? = null,
    ): Estimate? {
        if (routeOneWayKm != null && routeOneWayKm > 0.0) return Estimate(whole(routeOneWayKm * 2), Estimate.Source.ROUTE)
        val past = history.filter { it.destName.isNotBlank() && it.destName == destName }
            .mapNotNull { it.distanceKm }.filter { it > 0.0 }
        if (past.isNotEmpty()) return Estimate(whole(past.average() * 2), Estimate.Source.HISTORY)
        if (valid(destLat, destLng) && valid(curLat, curLng)) {
            val d = haversineKm(curLat!!, curLng!!, destLat!!, destLng!!)
            if (d > 0.05) return Estimate(whole(d * ROAD_FACTOR * 2), Estimate.Source.STRAIGHT)
        }
        return null
    }

    /** 추정값은 km 단위로 반올림한다(말하는 거리와 금액이 서로 맞도록). 1km 미만은 1km */
    private fun whole(km: Double): Double = Math.max(1.0, Math.rint(km))

    private fun valid(lat: Double?, lng: Double?): Boolean =
        lat != null && lng != null && !(lat == 0.0 && lng == 0.0) && lat in -90.0..90.0 && lng in -180.0..180.0

    fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1); val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a))
    }
}

/** 차가 말하는 문장 (순수 Kotlin). 화면 문구와 TTS가 같은 문장을 쓴다. */
object Speech {

    private fun limitWord(type: PlanType) = if (type == PlanType.LEASE) "약정거리" else "보험 할인 구간"

    /** 한 약정에 대한 목적지 영향 한 줄. 화면 표시용 */
    fun impactLine(i: Impact): String {
        val t = i.before.plan.type
        val rem = i.after.remainingKm
        return when {
            i.crosses && t == PlanType.LEASE ->
                "${t.label}: 약정거리를 ${Fmt.km(i.overKm)}km 넘습니다 · 초과 운행료 약 ${Fmt.won(i.costWon)}원"
            i.crosses && t == PlanType.INSURANCE -> {
                val from = i.before.tier?.let { "${Fmt.km(it.maxKm)}km 이하" } ?: "구간 밖"
                val to = i.after.tier?.let { "${Fmt.km(it.maxKm)}km 이하" } ?: "환급 없음"
                "${t.label}: 구간이 $from → ${to}로 바뀝니다 · 환급 약 ${Fmt.won(i.costWon)}원 감소"
            }
            i.before.status == PaceStatus.OVER && t == PlanType.LEASE ->
                "${t.label}: 이미 약정거리를 넘었습니다 · 이번 이동으로 약 ${Fmt.won(i.costWon)}원 추가"
            rem == null -> "${t.label}: 이미 모든 구간을 넘었습니다"
            else -> "${t.label}: ${limitWord(t)}까지 ${Fmt.km(max(0.0, rem))}km 남습니다"
        }
    }

    /** 목적지를 넣은 순간 말하는 문장. 넘는 약정이 있으면 그것부터 말한다. 모든 문장은 존댓말 완결문이다. */
    fun destinationWarning(destName: String, est: Estimate, impacts: List<Impact>): String {
        val head = "${destName}까지 왕복 약 ${Fmt.km(est.roundTripKm)}킬로미터입니다."
        if (impacts.isEmpty()) return "$head 등록된 약정이 없습니다."
        val crossing = impacts.filter { it.crosses }
        if (crossing.isEmpty()) {
            val tightest = impacts.filter { it.after.remainingKm != null }.minByOrNull { it.after.remainingKm!! }
                ?: return "$head 이미 한도를 넘은 상태입니다."
            if (tightest.after.remainingKm!! < 0) return "$head 이미 한도를 넘은 상태입니다."
            val base = "$head 다녀오셔도 ${limitWord(tightest.before.plan.type)}까지 ${Fmt.km(tightest.after.remainingKm!!)}킬로미터 남습니다."
            return listOfNotNull(base, advice(impacts)).joinToString(" ")
        }
        val parts = crossing.map { i ->
            when (i.before.plan.type) {
                PlanType.LEASE -> "다녀오시면 약정거리를 ${Fmt.km(i.overKm)}킬로미터 넘습니다. 초과 운행료는 약 ${Fmt.won(i.costWon)}원입니다."
                PlanType.INSURANCE -> "보험 할인 구간이 한 단계 내려가서 환급이 약 ${Fmt.won(i.costWon)}원 줄어듭니다."
            }
        }
        return (listOf(head) + parts + listOfNotNull(advice(impacts))).joinToString(" ")
    }

    /**
     * 경고 뒤에 붙이는 대안 한 문장. 화면과 TTS가 같은 문장을 쓴다.
     *  - 약정거리를 넘는 목적지: 약정 안에서 다녀올 수 있는 편도 거리(남은 거리의 절반, 내림)를 알려 준다.
     *  - 넘지 않는 목적지: 다녀온 뒤 만기까지 지켜야 할 하루 평균 거리를 알려 준다.
     * 리스·장기렌트 약정이 없거나, 이미 넘었거나, 만기가 지났으면 null.
     */
    fun advice(impacts: List<Impact>): String? {
        val lease = impacts.firstOrNull { it.before.plan.type == PlanType.LEASE } ?: return null
        val before = lease.before.remainingKm ?: return null
        if (before <= 0 || lease.before.remainingDays <= 0) return null
        if (lease.crosses) {
            val reach = Math.floor(before / 2).toLong()
            return if (reach < 1) null else "편도 ${Fmt.km(reach.toDouble())}킬로미터 이내 목적지는 약정거리 안에서 다녀오실 수 있습니다."
        }
        val daily = lease.after.dailyAllowanceKm ?: return null
        return "다녀오신 뒤에는 만기까지 하루 ${Fmt.km(Math.floor(daily))}킬로미터 이하로 달리시면 지킬 수 있습니다."
    }

    /** 운행이 끝났을 때 말하는 안내 */
    fun tripEnd(tripKm: Double, paces: List<Pace>): String {
        val head = "이번 운행은 ${Fmt.km(tripKm)}킬로미터였습니다."
        val p = primary(paces) ?: return head
        return "$head ${remainingSentence(p, withName = false)}"
    }

    /** "얼마 남았어?"에 대한 대답 */
    fun remaining(paces: List<Pace>): String {
        if (paces.isEmpty()) return "등록된 약정이 없습니다. 정차 중에 약정 탭에서 등록해 주세요."
        return paces.joinToString(" ") { remainingSentence(it, withName = true) }
    }

    /** 만기 예상에 대한 대답 */
    fun projection(paces: List<Pace>): String {
        if (paces.isEmpty()) return remaining(paces)
        return paces.joinToString(" ") { p ->
            val name = topic(p.plan.type)
            val proj = p.projectedKm ?: return@joinToString "$name 아직 기록이 하루가 안 되어 예상할 수 없습니다."
            when (p.plan.type) {
                PlanType.LEASE -> {
                    val over = proj - p.plan.limitKm
                    if (over > 0) "$name 이 페이스면 만기에 ${Fmt.km(over)}킬로미터를 넘습니다. 초과 운행료는 약 ${Fmt.won(p.projectedAmountWon)}원입니다."
                    else "$name 이 페이스면 만기에 ${Fmt.km(-over)}킬로미터가 남습니다."
                }
                PlanType.INSURANCE ->
                    "$name 이 페이스면 만기 주행거리가 약 ${Fmt.km(proj)}킬로미터입니다. 예상 환급은 약 ${Fmt.won(p.projectedAmountWon)}원입니다."
            }
        }
    }

    /** 문장 주어: "리스·장기렌트는" / "보험 마일리지는" */
    private fun topic(type: PlanType) = if (type == PlanType.LEASE) "리스·장기렌트는" else "보험 마일리지는"

    private fun remainingSentence(p: Pace, withName: Boolean): String {
        val rem = p.remainingKm
        val word = limitWord(p.plan.type)
        val name = if (withName) topic(p.plan.type) + " " else ""
        return when {
            rem == null -> "${name}모든 할인 구간을 넘었습니다."
            rem < 0 -> "${name}${word}${if (p.plan.type == PlanType.LEASE) "를" else "을"} ${Fmt.km(-rem)}킬로미터 넘었습니다."
            p.remainingDays <= 0 -> "${name}${word}까지 ${Fmt.km(rem)}킬로미터를 남기고 만기가 되었습니다."
            else -> "${name}${word}까지 ${Fmt.km(rem)}킬로미터, 만기까지 ${p.remainingDays}일 남았습니다. 하루 ${Fmt.km(p.dailyAllowanceKm)}킬로미터 이하로 달리시면 지킬 수 있습니다."
        }
    }

    /** 가장 여유가 적은 약정 */
    fun primary(paces: List<Pace>): Pace? =
        paces.minByOrNull { it.remainingKm ?: Double.NEGATIVE_INFINITY }
}

/** 말하기 전 처리 (순수 Kotlin) */
object SpeechText {
    /** 문장 단위로 나눈다. TTS에 한 문장씩 넘겨 긴 안내가 중간에 잘리지 않게 한다. */
    fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[.?!])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }

    /** 읽기용: 숫자 사이 쉼표를 뺀다("31,600원" → "31600원"). 쉼표에서 끊어 읽는 것을 막는다. */
    fun forTts(sentence: String): String = sentence.replace(Regex("(?<=\\d),(?=\\d)"), "")
}

/** 음성 질문 분류 */
object VoiceQuery {
    enum class Intent { REMAINING, PROJECTION }

    private val projectionWords = listOf("페이스", "만기", "예상", "이대로", "넘", "초과", "환급")

    /** 알아듣지 못했거나 말이 없으면 '남은 거리'로 답한다. */
    fun classify(text: String?): Intent {
        val t = text?.trim().orEmpty()
        return if (projectionWords.any { t.contains(it) }) Intent.PROJECTION else Intent.REMAINING
    }
}
