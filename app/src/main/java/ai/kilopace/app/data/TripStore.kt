package ai.kilopace.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 운행 기록 저장소. 차량 로컬(앱 전용 디렉터리)의 JSON 파일 하나에 저장한다. 서버 전송 없음.
 */
class TripStore(context: Context) {

    private val file = File(context.filesDir, "trips.json")
    private val records = mutableListOf<TripRecord>()
    private val listeners = mutableListOf<() -> Unit>()

    init { load() }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    @Synchronized
    fun all(): List<TripRecord> = records.sortedByDescending { it.startAt }

    @Synchronized
    fun closed(): List<TripRecord> = all().filter { !it.isOpen }

    @Synchronized
    fun openTrip(): TripRecord? = records.firstOrNull { it.isOpen }

    @Synchronized
    fun upsert(r: TripRecord) {
        val i = records.indexOfFirst { it.id == r.id }
        if (i >= 0) records[i] = r else records += r
        save(); notifyChanged()
    }

    @Synchronized
    fun delete(id: String) {
        records.removeAll { it.id == id }
        save(); notifyChanged()
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) records += TripRecord.fromJson(arr.getJSONObject(i))
        }
    }

    private fun save() {
        val arr = JSONArray()
        records.forEach { arr.put(it.toJson()) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    private fun notifyChanged() { listeners.toList().forEach { it() } }
}

/** 약정 저장소. 종류별로 1건씩(리스·장기렌트, 보험 마일리지) 차량 로컬에 저장한다. */
class PlanStore(context: Context) {
    private val file = File(context.filesDir, "plans.json")
    private val plans = linkedMapOf<PlanType, Plan>()
    private val listeners = mutableListOf<() -> Unit>()

    init {
        if (file.exists()) runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) Plan.fromJson(arr.getJSONObject(i)).let { plans[it.type] = it }
        }
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    @Synchronized fun all(): List<Plan> = PlanType.entries.mapNotNull { plans[it] }
    @Synchronized fun get(type: PlanType): Plan? = plans[type]

    @Synchronized
    fun put(plan: Plan) { plans[plan.type] = plan; save() }

    @Synchronized
    fun remove(type: PlanType) { plans.remove(type); save() }

    private fun save() {
        val arr = JSONArray()
        plans.values.forEach { arr.put(it.toJson()) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
        listeners.toList().forEach { it() }
    }
}

/** 사용자 설정 (SharedPreferences) */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("kilopace", Context.MODE_PRIVATE)

    /** 운행 종료 판단: 계기판이 멈춘 시간(초). 시연 기본 20초, 실차 권장 180초 */
    var stallSeconds: Int
        get() = p.getInt("stallSeconds", 20)
        set(v) = p.edit().putInt("stallSeconds", v).apply()
    var voiceEnabled: Boolean
        get() = p.getBoolean("voice", true)
        set(v) = p.edit().putBoolean("voice", v).apply()
}

/** 내보내기: 약정 요약(QR용)과 운행 CSV */
object Report {
    /** 휴대폰으로 받아 가는 요약. 참고용이며 보험사·렌트사 제출 서류를 대신하지 않는다. */
    fun summary(paces: List<Pace>, odoKm: Double?, now: Long, tripCount: Int): String = buildString {
        append("[킬로페이스 주행거리 요약 · 참고용]\n")
        append("작성 ").append(Fmt.date(now)).append(" · 계기판 ").append(Fmt.km(odoKm)).append(" km (차량 신호)\n")
        paces.forEach { p ->
            val pl = p.plan
            append("\n■ ").append(pl.type.label).append(' ').append(Fmt.date(pl.startAt)).append(" ~ ").append(Fmt.date(pl.endAt)).append('\n')
            append("시작 계기판 ").append(Fmt.km(pl.startOdoKm)).append(" km · 사용 ").append(Fmt.km(p.usedKm)).append(" km\n")
            when (pl.type) {
                PlanType.LEASE -> {
                    append("약정 ").append(Fmt.km(pl.limitKm)).append(" km · 남음 ").append(Fmt.km(p.remainingKm)).append(" km\n")
                    if (p.amountWon > 0) append("초과 운행료 약 ").append(Fmt.won(p.amountWon)).append("원\n")
                }
                PlanType.INSURANCE -> {
                    append("현재 구간 ").append(p.tier?.let { "${Fmt.km(it.maxKm)} km 이하 (${Fmt.num(it.discountPct)}%)" } ?: "구간 밖").append('\n')
                    append("예상 환급 약 ").append(Fmt.won(p.amountWon)).append("원\n")
                }
            }
            p.projectedKm?.let { append("이 페이스의 만기 예상 ").append(Fmt.km(it)).append(" km\n") }
        }
        append("\n기록된 운행 ").append(tripCount).append("건")
    }

    fun csv(trips: List<TripRecord>): String = buildString {
        append("출발일시,출발 계기판(km),도착 계기판(km),주행거리(km),목적지\n")
        trips.sortedBy { it.startAt }.forEach { t ->
            append(TripRecord.formatDate(t.startAt)).append(',')
            append(t.odoStartKm?.let { Fmt.num(it) } ?: "").append(',')
            append(t.odoEndKm?.let { Fmt.num(it) } ?: "").append(',')
            append(t.distanceKm?.let { Fmt.num(it) } ?: "").append(',')
            append(escape(t.destName)).append('\n')
        }
    }

    private fun escape(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun writeCsv(context: Context, csv: String): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        // 엑셀에서 한글이 깨지지 않도록 BOM을 붙인다
        return File(dir, "kilopace_trips.csv").apply { writeText("﻿" + csv) }
    }
}
