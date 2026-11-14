package ai.kilopace.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaceCalcTest {

    private val day = 86_400_000L
    private val t0 = 1_700_000_000_000L

    /** 730일 계약, 약정 20,000km, 초과 100원/km, 시작 계기판 100km */
    private val lease = Plan(PlanType.LEASE, t0, t0 + 730 * day, startOdoKm = 100.0, limitKm = 20_000.0, feePerKm = 100.0)

    private val tiers = listOf(Tier(3000.0, 32.0), Tier(5000.0, 25.0), Tier(7000.0, 20.0), Tier(10000.0, 15.0))
    private val ins = Plan(PlanType.INSURANCE, t0, t0 + 365 * day, startOdoKm = 1000.0, premiumWon = 680_000.0, tiers = tiers)

    @Test fun `lease remaining and daily allowance`() {
        val p = PaceCalc.pace(lease, odoKm = 19_660.0, now = t0 + 715 * day)
        assertEquals(19_560.0, p.usedKm, 0.001)
        assertEquals(440.0, p.remainingKm!!, 0.001)
        assertEquals(15, p.remainingDays)
        assertEquals(440.0 / 15, p.dailyAllowanceKm!!, 0.001)
        assertEquals(0.0, p.amountWon, 0.001)
    }

    @Test fun `lease projection over the limit is TIGHT before it is OVER`() {
        // 365일에 11,000km → 만기 22,000km 예상
        val p = PaceCalc.pace(lease, odoKm = 11_100.0, now = t0 + 365 * day)
        assertEquals(22_000.0, p.projectedKm!!, 0.001)
        assertEquals(PaceStatus.TIGHT, p.status)
        assertEquals(200_000.0, p.projectedAmountWon!!, 0.001)
    }

    @Test fun `lease on a slow pace is OK`() {
        val p = PaceCalc.pace(lease, odoKm = 5_100.0, now = t0 + 365 * day)
        assertEquals(PaceStatus.OK, p.status)
        assertEquals(0.0, p.projectedAmountWon!!, 0.001)
    }

    @Test fun `lease over the limit charges per km`() {
        val p = PaceCalc.pace(lease, odoKm = 20_400.0, now = t0 + 720 * day)
        assertEquals(PaceStatus.OVER, p.status)
        assertEquals(-300.0, p.remainingKm!!, 0.001)
        assertEquals(30_000.0, p.amountWon, 0.001)
        assertEquals(0.0, p.dailyAllowanceKm!!, 0.001)
    }

    @Test fun `no projection during the first day`() {
        val p = PaceCalc.pace(lease, odoKm = 130.0, now = t0 + day / 2)
        assertNull(p.projectedKm)
        assertEquals(PaceStatus.OK, p.status)
    }

    @Test fun `odometer below the start value counts as zero used`() {
        val p = PaceCalc.pace(lease, odoKm = 50.0, now = t0 + 10 * day)
        assertEquals(0.0, p.usedKm, 0.001)
    }

    @Test fun `after expiry remaining days is zero and allowance does not divide by zero`() {
        val p = PaceCalc.pace(lease, odoKm = 10_100.0, now = t0 + 800 * day)
        assertEquals(0, p.remainingDays)
        assertEquals(10_000.0, p.dailyAllowanceKm!!, 0.001)
    }

    @Test fun `insurance tier and refund`() {
        val p = PaceCalc.pace(ins, odoKm = 7_360.0, now = t0 + 259 * day)   // 6,360km 사용
        assertEquals(7000.0, p.tier!!.maxKm, 0.001)
        assertEquals(640.0, p.remainingKm!!, 0.001)
        assertEquals(136_000.0, p.amountWon, 0.001)                      // 680,000 × 20%
    }

    @Test fun `insurance tier boundary is inclusive`() {
        assertEquals(5000.0, PaceCalc.tierFor(ins, 5000.0)!!.maxKm, 0.001)
        assertEquals(7000.0, PaceCalc.tierFor(ins, 5000.1)!!.maxKm, 0.001)
    }

    @Test fun `insurance beyond the last tier has no refund`() {
        val p = PaceCalc.pace(ins, odoKm = 12_000.0, now = t0 + 300 * day)
        assertNull(p.tier); assertNull(p.remainingKm)
        assertEquals(0.0, p.amountWon, 0.001)
        assertEquals(PaceStatus.OVER, p.status)
    }

    @Test fun `impact - lease trip that crosses the limit`() {
        val i = PaceCalc.impact(lease, odoKm = 19_660.0, extraKm = 845.0, now = t0 + 715 * day)
        assertTrue(i.crosses)
        assertEquals(405.0, i.overKm, 0.001)
        assertEquals(40_500.0, i.costWon, 0.001)
    }

    @Test fun `impact - short trip stays inside`() {
        val i = PaceCalc.impact(lease, odoKm = 19_660.0, extraKm = 30.0, now = t0 + 715 * day)
        assertFalse(i.crosses)
        assertEquals(0.0, i.overKm, 0.001)
        assertEquals(0.0, i.costWon, 0.001)
        assertEquals(410.0, i.after.remainingKm!!, 0.001)
    }

    @Test fun `impact - already over only adds the extra fee`() {
        val i = PaceCalc.impact(lease, odoKm = 20_400.0, extraKm = 100.0, now = t0 + 720 * day)
        assertFalse(i.crosses)
        assertEquals(10_000.0, i.costWon, 0.001)
    }

    @Test fun `impact - insurance trip that drops a tier loses the refund difference`() {
        val i = PaceCalc.impact(ins, odoKm = 7_360.0, extraKm = 845.0, now = t0 + 259 * day)
        assertTrue(i.crosses)
        assertEquals(10000.0, i.after.tier!!.maxKm, 0.001)
        assertEquals(34_000.0, i.costWon, 0.001)                          // 20% → 15%
    }

    @Test fun `impact - negative extra distance is treated as zero`() {
        val i = PaceCalc.impact(lease, odoKm = 19_660.0, extraKm = -5.0, now = t0 + 715 * day)
        assertEquals(0.0, i.extraKm, 0.001)
        assertFalse(i.crosses)
    }

    @Test fun `tiers parse from text, sort, and skip bad items`() {
        val t = Plan.parseTiers("7000:20, 3000 : 32%, abc, 5,000=25, -1:3")
        assertEquals(listOf(3000.0, 5000.0, 7000.0), t.map { it.maxKm })
        assertEquals(listOf(32.0, 25.0, 20.0), t.map { it.discountPct })
        assertTrue(Plan.parseTiers("").isEmpty())
        assertTrue(Plan.parseTiers(null).isEmpty())
    }

    @Test fun `plan survives a JSON round trip`() {
        assertEquals(ins, Plan.fromJson(JSONObject(ins.toJson().toString())))
        assertEquals(lease, Plan.fromJson(JSONObject(lease.toJson().toString())))
    }

    @Test fun `number formatting`() {
        assertEquals("1,234", Fmt.km(1234.4))
        assertEquals("—", Fmt.km(null))
        assertEquals("25", Fmt.num(25.0))
        assertEquals("12.5", Fmt.num(12.5))
        assertNull(Fmt.parseDate("2026-13-40"))
        assertNull(Fmt.parseDate("내일"))
        assertEquals("2026-11-30", Fmt.date(Fmt.parseDate("2026-11-30")!!))
    }
}
