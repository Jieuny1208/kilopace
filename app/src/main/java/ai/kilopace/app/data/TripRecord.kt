package ai.kilopace.app.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 운행 1건: 출발·도착 시점의 계기판 누적거리와 목적지 */
data class TripRecord(
    val id: String,
    val startAt: Long,                // epoch millis
    val endAt: Long?,                 // null = 운행 중
    val odoStartKm: Double?,
    val odoEndKm: Double?,
    val destName: String,             // 내비 목적지 이름 (다음 왕복 추정에 쓴다)
    val destAddr: String,
    val startAddr: String,
) {
    val distanceKm: Double?
        get() {
            val s = odoStartKm ?: return null
            val e = odoEndKm ?: return null
            return (e - s).coerceAtLeast(0.0)
        }

    val isOpen: Boolean get() = endAt == null

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("startAt", startAt); put("endAt", endAt ?: JSONObject.NULL)
        put("odoStartKm", odoStartKm ?: JSONObject.NULL); put("odoEndKm", odoEndKm ?: JSONObject.NULL)
        put("destName", destName); put("destAddr", destAddr); put("startAddr", startAddr)
    }

    companion object {
        private fun JSONObject.optDoubleOrNull(k: String): Double? = if (isNull(k)) null else optDouble(k)
        private fun JSONObject.optLongOrNull(k: String): Long? = if (isNull(k)) null else optLong(k)

        fun fromJson(o: JSONObject): TripRecord = TripRecord(
            id = o.getString("id"),
            startAt = o.getLong("startAt"),
            endAt = o.optLongOrNull("endAt"),
            odoStartKm = o.optDoubleOrNull("odoStartKm"),
            odoEndKm = o.optDoubleOrNull("odoEndKm"),
            destName = o.optString("destName"),
            destAddr = o.optString("destAddr"),
            startAddr = o.optString("startAddr"),
        )

        private val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.KOREA)
        private val dayNames = arrayOf("", "일", "월", "화", "수", "목", "금", "토")

        /** 예: 11-15(일) 06:40 */
        fun formatDate(millis: Long): String {
            val c = Calendar.getInstance().apply { timeInMillis = millis }
            val s = dateFmt.format(Date(millis))
            return s.substring(0, 5) + "(" + dayNames[c.get(Calendar.DAY_OF_WEEK)] + ")" + s.substring(5)
        }
    }
}
