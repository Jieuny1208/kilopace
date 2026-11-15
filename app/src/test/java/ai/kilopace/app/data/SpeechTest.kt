package ai.kilopace.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTest {

    private val day = 86_400_000L
    private val t0 = 1_700_000_000_000L
    private val lease = Plan(PlanType.LEASE, t0, t0 + 730 * day, startOdoKm = 100.0, limitKm = 20_000.0, feePerKm = 100.0)
    private val ins = Plan(
        PlanType.INSURANCE, t0 + 456 * day, t0 + 821 * day, startOdoKm = 13_300.0, premiumWon = 680_000.0,
        tiers = listOf(Tier(5000.0, 25.0), Tier(7000.0, 20.0), Tier(10000.0, 15.0)),
    )
    private val now = t0 + 715 * day
    private val odo = 19_660.0

    private fun trip(dest: String, start: Double, end: Double) =
        TripRecord("id$start", t0, t0 + 1000, start, end, dest, "", "")

    // ---- 왕복 거리 추정 ----

    @Test fun `estimate uses the average of past trips to the same destination`() {
        val h = listOf(trip("부산역", 100.0, 500.0), trip("부산역", 1000.0, 1420.0), trip("현대자동차 본사", 0.0, 12.0))
        val e = TripEstimator.roundTrip("부산역", null, null, null, null, h)!!
        assertEquals(Estimate.Source.HISTORY, e.source)
        assertEquals(820.0, e.roundTripKm, 0.001)
    }

    @Test fun `navi route distance wins over history and straight line`() {
        val h = listOf(trip("부산역", 100.0, 500.0))
        val e = TripEstimator.roundTrip("부산역", 35.1151, 129.0415, 37.5663, 126.9779, h, routeOneWayKm = 378.0)!!
        assertEquals(Estimate.Source.ROUTE, e.source)
        assertEquals(756.0, e.roundTripKm, 0.001)
        // 경로 거리가 0이면 쓰지 않는다
        assertEquals(Estimate.Source.HISTORY, TripEstimator.roundTrip("부산역", null, null, null, null, h, routeOneWayKm = 0.0)!!.source)
    }

    @Test fun `estimate falls back to straight line times road factor`() {
        // 서울시청 → 부산역, 직선 약 320km
        val e = TripEstimator.roundTrip("부산역", 35.1151, 129.0415, 37.5663, 126.9779, emptyList())!!
        assertEquals(Estimate.Source.STRAIGHT, e.source)
        val straight = TripEstimator.haversineKm(37.5663, 126.9779, 35.1151, 129.0415)
        assertTrue(straight in 310.0..335.0)
        assertEquals(Math.rint(straight * TripEstimator.ROAD_FACTOR * 2), e.roundTripKm, 0.001)
    }

    @Test fun `no estimate without history or usable coordinates`() {
        assertNull(TripEstimator.roundTrip("어딘가", null, null, 37.5, 127.0, emptyList()))
        assertNull(TripEstimator.roundTrip("어딘가", 0.0, 0.0, 37.5, 127.0, emptyList()))      // 0,0은 좌표 없음으로 본다
        assertNull(TripEstimator.roundTrip("어딘가", 37.5, 127.0, 37.5, 127.0, emptyList()))    // 제자리
        assertNull(TripEstimator.roundTrip("어딘가", 37.5, 127.0, null, null, listOf(trip("다른 곳", 0.0, 9.0))))
    }

    @Test fun `zero-distance history is ignored`() {
        assertNull(TripEstimator.roundTrip("부산역", null, null, null, null, listOf(trip("부산역", 10.0, 10.0))))
    }

    // ---- 목적지 경고 문장 ----

    @Test fun `warning names the overage and the fee when the lease limit is crossed`() {
        val est = Estimate(845.0, Estimate.Source.STRAIGHT)
        val s = Speech.destinationWarning("부산역", est, listOf(PaceCalc.impact(lease, odo, 845.0, now)))
        assertTrue(s, s.startsWith("부산역까지 왕복 약 845킬로미터입니다."))
        assertTrue(s, s.contains("다녀오시면 약정거리를 405킬로미터 넘습니다."))
        assertTrue(s, s.contains("40,500원"))
    }

    @Test fun `warning mentions both contracts when both are crossed`() {
        val est = Estimate(845.0, Estimate.Source.STRAIGHT)
        val s = Speech.destinationWarning("부산역", est, listOf(PaceCalc.impact(lease, odo, 845.0, now), PaceCalc.impact(ins, odo, 845.0, now)))
        assertTrue(s, s.contains("다녀오시면 약정거리를 405킬로미터 넘습니다."))
        assertTrue(s, s.contains("환급이 약 34,000원 줄어듭니다"))
    }

    @Test fun `short trip reports the tightest remaining margin instead of a warning`() {
        val est = Estimate(30.0, Estimate.Source.HISTORY)
        val s = Speech.destinationWarning("현대자동차 본사", est, listOf(PaceCalc.impact(lease, odo, 30.0, now), PaceCalc.impact(ins, odo, 30.0, now)))
        assertFalse(s, s.contains("넘습니다"))
        assertTrue(s, s.contains("다녀오셔도 약정거리까지 410킬로미터 남습니다."))
    }

    @Test fun `crossing warning ends with the one-way distance that still fits`() {
        val s = Speech.destinationWarning("부산역", Estimate(845.0, Estimate.Source.ROUTE), listOf(PaceCalc.impact(lease, odo, 845.0, now), PaceCalc.impact(ins, odo, 845.0, now)))
        assertTrue(s, s.endsWith("편도 220킬로미터 이내 목적지는 약정거리 안에서 다녀오실 수 있습니다."))
    }

    @Test fun `short trip adds the daily allowance after the trip`() {
        val s = Speech.destinationWarning("현대자동차 본사", Estimate(30.0, Estimate.Source.ROUTE), listOf(PaceCalc.impact(lease, odo, 30.0, now)))
        assertTrue(s, s.endsWith("다녀오신 뒤에는 만기까지 하루 27킬로미터 이하로 달리시면 지킬 수 있습니다."))
    }

    @Test fun `no advice without a lease, when already over, or after maturity`() {
        assertNull(Speech.advice(listOf(PaceCalc.impact(ins, odo, 845.0, now))))
        assertNull(Speech.advice(listOf(PaceCalc.impact(lease, 20_400.0, 30.0, now))))
        assertNull(Speech.advice(listOf(PaceCalc.impact(lease, odo, 30.0, t0 + 731 * day))))
        assertNull(Speech.advice(emptyList()))
    }

    @Test fun `warning without contracts says so`() {
        val s = Speech.destinationWarning("부산역", Estimate(845.0, Estimate.Source.STRAIGHT), emptyList())
        assertTrue(s, s.contains("등록된 약정이 없습니다"))
    }

    @Test fun `impact line for screen`() {
        assertTrue(Speech.impactLine(PaceCalc.impact(lease, odo, 845.0, now)).contains("405km 넘습니다"))
        assertTrue(Speech.impactLine(PaceCalc.impact(lease, odo, 30.0, now)).contains("410km 남습니다"))
        val line = Speech.impactLine(PaceCalc.impact(ins, odo, 845.0, now))
        assertTrue(line, line.contains("7,000km 이하 → 10,000km 이하"))
    }

    // ---- 운행 종료·질문 대답 ----

    @Test fun `trip end line uses the tightest contract`() {
        val paces = listOf(PaceCalc.pace(lease, odo + 23, now), PaceCalc.pace(ins, odo + 23, now))
        val s = Speech.tripEnd(23.0, paces)
        assertTrue(s, s.startsWith("이번 운행은 23킬로미터였습니다."))
        assertTrue(s, s.contains("약정거리까지 417킬로미터, 만기까지 15일 남았습니다"))
        assertTrue(s, s.contains("하루 28킬로미터 이하로 달리시면 지킬 수 있습니다."))
    }

    @Test fun `trip end line without contracts is just the distance`() {
        assertEquals("이번 운행은 23킬로미터였습니다.", Speech.tripEnd(23.0, emptyList()))
    }

    @Test fun `remaining answer covers every contract and the empty case`() {
        val s = Speech.remaining(listOf(PaceCalc.pace(lease, odo, now), PaceCalc.pace(ins, odo, now)))
        assertTrue(s, s.contains("리스·장기렌트는 약정거리까지 440킬로미터"))
        assertTrue(s, s.contains("보험 마일리지는 보험 할인 구간까지 640킬로미터"))
        assertTrue(Speech.remaining(emptyList()).contains("등록된 약정이 없습니다"))
    }

    @Test fun `remaining answer when already over`() {
        val s = Speech.remaining(listOf(PaceCalc.pace(lease, 20_400.0, now)))
        assertTrue(s, s.contains("약정거리를 300킬로미터 넘었습니다"))
    }

    @Test fun `projection answer`() {
        val s = Speech.projection(listOf(PaceCalc.pace(lease, 11_100.0, t0 + 365 * day)))
        assertTrue(s, s.contains("만기에 2,000킬로미터를 넘습니다."))
        assertTrue(s, s.contains("200,000원"))
        val early = Speech.projection(listOf(PaceCalc.pace(lease, 130.0, t0 + day / 2)))
        assertTrue(early, early.contains("예상할 수 없습니다"))
    }

    @Test fun `every spoken sentence is a complete polite sentence`() {
        val est = Estimate(845.0, Estimate.Source.ROUTE)
        val both = listOf(PaceCalc.impact(lease, odo, 845.0, now), PaceCalc.impact(ins, odo, 845.0, now))
        val paces = listOf(PaceCalc.pace(lease, odo, now), PaceCalc.pace(ins, odo, now))
        val all = listOf(
            Speech.destinationWarning("부산역", est, both),
            Speech.destinationWarning("현대자동차 본사", Estimate(18.0, Estimate.Source.ROUTE), listOf(PaceCalc.impact(lease, odo, 18.0, now))),
            Speech.destinationWarning("부산역", est, emptyList()),
            Speech.tripEnd(8.0, paces), Speech.tripEnd(8.0, emptyList()),
            Speech.remaining(paces), Speech.remaining(emptyList()), Speech.remaining(listOf(PaceCalc.pace(lease, 20_400.0, now))),
            Speech.projection(paces), Speech.projection(listOf(PaceCalc.pace(lease, 130.0, t0 + day / 2))),
        )
        for (text in all) for (s in SpeechText.sentences(text)) {
            assertTrue("존댓말 완결문이 아님: $s", s.endsWith("니다.") || s.endsWith("주세요."))
        }
    }

    @Test fun `speech is split into sentences and number commas are dropped for reading`() {
        val s = SpeechText.sentences("부산역까지 왕복 약 756킬로미터입니다. 초과 운행료는 약 31,600원입니다.")
        assertEquals(2, s.size)
        assertEquals("초과 운행료는 약 31600원입니다.", SpeechText.forTts(s[1]))
        assertEquals("약정거리까지 432킬로미터, 만기까지 15일 남았습니다.", SpeechText.forTts("약정거리까지 432킬로미터, 만기까지 15일 남았습니다."))
        assertTrue(SpeechText.sentences("  ").isEmpty())
    }

    @Test fun `voice query classification`() {
        assertEquals(VoiceQuery.Intent.REMAINING, VoiceQuery.classify("얼마 남았어"))
        assertEquals(VoiceQuery.Intent.REMAINING, VoiceQuery.classify(null))
        assertEquals(VoiceQuery.Intent.REMAINING, VoiceQuery.classify("  "))
        assertEquals(VoiceQuery.Intent.PROJECTION, VoiceQuery.classify("이 페이스면 어떻게 돼"))
        assertEquals(VoiceQuery.Intent.PROJECTION, VoiceQuery.classify("만기에 넘어?"))
    }

    // ---- 기록·내보내기 ----

    @Test fun `trip record JSON round trip keeps nulls`() {
        val open = TripRecord("a", 1L, null, 10.0, null, "", "", "서울")
        assertEquals(open, TripRecord.fromJson(JSONObject(open.toJson().toString())))
        assertTrue(open.isOpen); assertNull(open.distanceKm)
        assertEquals(0.0, trip("x", 10.0, 5.0).distanceKm!!, 0.001)       // 계기판 역행은 0으로
    }

    @Test fun `csv has a header, one line per trip in time order, and escapes commas`() {
        val a = TripRecord("a", 2000L, 3000L, 100.0, 123.0, "부산역, 광장", "", "")
        val b = TripRecord("b", 1000L, 1500L, 90.0, 100.0, "현대자동차 본사", "", "")
        val lines = Report.csv(listOf(a, b)).trim().lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("출발일시,"))
        assertTrue(lines[1].endsWith(",90,100,10,현대자동차 본사"))
        assertTrue(lines[2].endsWith(",100,123,23,\"부산역, 광장\""))
    }

    @Test fun `summary lists each contract and stays within one QR code`() {
        val s = Report.summary(listOf(PaceCalc.pace(lease, odo, now), PaceCalc.pace(ins, odo, now)), odo, now, 7)
        assertTrue(s, s.contains("참고용"))
        assertTrue(s, s.contains("약정 20,000 km · 남음 440 km"))
        assertTrue(s, s.contains("현재 구간 7,000 km 이하 (20%)"))
        assertTrue(s, s.contains("기록된 운행 7건"))
        assertTrue(s.toByteArray().size <= QrEncoder.MAX_BYTES)
    }
}
