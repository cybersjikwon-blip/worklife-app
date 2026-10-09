package kr.worklife.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

class CoreTest {
    private val WL = 37.41 to 127.257
    private val c = Company(lat = WL.first, lon = WL.second)
    private val s = AppSettings()
    private val D = LocalDate.of(2026, 10, 14) // 수
    private val OCT = YearMonth.of(2026, 10)
    private val NOV1 = LocalDate.of(2026, 11, 1)

    private fun at(t: String): LocalDateTime = D.atTime(hm(t))
    private fun smp(t: String, dist: Double, acc: Double = 10.0): LocSample {
        val (la, lo) = offsetPoint(WL.first, WL.second, northM = dist)
        return LocSample(at(t), la, lo, acc)
    }
    private fun inside(until: String): List<LocSample> {
        val out = mutableListOf<LocSample>()
        var t = at("05:50")
        while (!t.isAfter(at(until))) { val (la, lo) = offsetPoint(WL.first, WL.second, 30.0); out += LocSample(t, la, lo); t = t.plusMinutes(5) }
        return out
    }
    private fun tr(cc: Company = c, ss: AppSettings = s) = GeofenceTracker(cc, ss, D)

    // ── 기본 규칙
    @Test fun scheduled8h() = assertEquals(480, c.scheduledMin())
    @Test fun baseIsMinWageX209() = assertEquals(2_156_880, s.minWage(2026) * 209)
    @Test fun minWageByYear() {
        assertEquals(10320, s.minWage(2026)); assertEquals(10700, s.minWage(2027)); assertEquals(10700, s.minWage(2031))
        assertEquals(11000, s.copy(minWageOverride = 11000).minWage(2026))
        assertEquals(10700 * 209, s.baseFor(c.copy(baseLinkedToMinWage = true), 2027))
    }

    @Test fun userExample17h() {
        val recs = buildMonth(c, OCT, mapOf(D to DayRecord(D, 1, Status.WORK, hm("06:00"), hm("17:00"), Source.AUTO)), NOV1)
        val p = calcPayroll(c, s, OCT, recs, NOV1)
        assertEquals(120, p.overtimeMin); assertEquals(20_640, p.overtimePay)
        assertEquals(2_156_880 + 20_640 + 100_000, p.gross)
    }

    @Test fun overtimeUnit() {
        mapOf("15:00" to 0, "15:29" to 0, "15:30" to 30, "16:59" to 90, "14:30" to 0).forEach { (o, e) ->
            assertEquals(o, e, overtimeMin(c, DayRecord(D, 1, Status.WORK, hm("06:00"), hm(o))))
        }
    }

    @Test fun legalRate() {
        val p = calcPayroll(c.copy(overtimeRate = 1.5), s, OCT, listOf(DayRecord(D, 1, Status.WORK, hm("06:00"), hm("17:00"))), D)
        assertEquals(30_960, p.overtimePay)
    }

    // ── 휴일
    @Test fun holidays() {
        val cal = HolidayCalendar(c)
        assertTrue(!cal.isWorkday(LocalDate.of(2026, 10, 9)))
        assertTrue(!cal.isWorkday(LocalDate.of(2026, 10, 5)))
        assertTrue(!cal.isWorkday(LocalDate.of(2026, 10, 10)))
        assertTrue(!cal.isWorkday(LocalDate.of(2030, 1, 1)))
        assertTrue(cal.isWorkday(LocalDate.of(2026, 10, 8)))
        assertEquals(20, cal.workdays(OCT).size)
    }

    @Test fun companyHolidays() {
        val cal = HolidayCalendar(c.copy(extraHolidays = setOf("2026-10-08"), extraWorkdays = setOf("2026-10-10")))
        assertTrue(!cal.isWorkday(LocalDate.of(2026, 10, 8))); assertTrue(cal.isWorkday(LocalDate.of(2026, 10, 10)))
    }

    // ── 만근/결근
    @Test fun bonusLostOnAbsence() {
        val p = calcPayroll(c, s, OCT, buildMonth(c, OCT, mapOf(D to DayRecord(D, 1, Status.ABSENT)), NOV1), NOV1)
        assertEquals(0, p.bonus); assertEquals(82_560, p.absenceDeduct)
    }
    @Test fun leaveKeepsBonus() =
        assertEquals(100_000, calcPayroll(c, s, OCT, buildMonth(c, OCT, mapOf(D to DayRecord(D, 1, Status.LEAVE)), NOV1), NOV1).bonus)
    @Test fun bonusPending() = assertEquals("예정", calcPayroll(c, s, OCT, buildMonth(c, OCT, emptyMap(), D), D).bonusStatus)
    @Test fun holidayWork() {
        val h = LocalDate.of(2026, 10, 10)
        val p = calcPayroll(c, s, OCT, listOf(DayRecord(h, 1, Status.HOLIDAY_WORK, hm("06:00"), hm("15:00"))), D)
        assertEquals(480, p.holidayWorkMin); assertEquals((8 * 10320 * 1.5).toInt(), p.holidayWorkPay)
    }

    // ── 이직
    @Test fun companyChangeMidMonth() {
        val old = c.copy(id = 1, endDate = LocalDate.of(2026, 10, 15))
        val new = c.copy(id = 2, name = "새회사", startDate = LocalDate.of(2026, 10, 16))
        val pOld = calcPayroll(old, s, OCT, buildMonth(old, OCT, emptyMap(), NOV1), NOV1)
        val pNew = calcPayroll(new, s, OCT, buildMonth(new, OCT, emptyMap(), NOV1), NOV1)
        assertEquals(2_156_880L * 15 / 31, pOld.base.toLong()); assertEquals(0, pOld.bonus)
        assertEquals(2_156_880L * 16 / 31, pNew.base.toLong())
        assertEquals(15, buildMonth(old, OCT, emptyMap(), NOV1).size)
    }

    // ── 지오펜스
    @Test fun exitAfterOvertime() { val g = tr(); g.feedAll(inside("17:00") + smp("17:03", 300.0) + smp("17:14", 800.0)); assertEquals(at("17:00") to Source.AUTO, g.finalize()) }
    @Test fun shortErrandIgnored() { val g = tr(); g.feedAll(inside("15:20") + listOf(smp("15:25", 300.0), smp("15:30", 300.0), smp("15:34", 20.0), smp("16:00", 20.0), smp("16:05", 300.0), smp("16:16", 400.0))); assertEquals(at("16:00"), g.finalize().first) }
    @Test fun fastExitByCar() { val g = tr(); g.feedAll(inside("15:40") + smp("15:42", 1500.0) + smp("15:44", 3000.0)); assertEquals(at("15:40"), g.checkout) }
    @Test fun gpsJumpIgnored() { val g = tr(); g.feedAll(inside("16:00") + listOf(smp("15:31", 500.0, 200.0), smp("16:05", 30.0), smp("16:10", 500.0), smp("16:21", 900.0))); assertEquals(at("16:05"), g.finalize().first) }
    @Test fun singleOutlierNeedsTwo() { val g = tr(ss = s.copy(exitConfirmMin = 0)); g.feedAll(inside("15:30") + smp("15:35", 400.0) + smp("15:40", 30.0)); assertNull(g.checkout) }
    @Test fun exitBeforeEndIsNotCheckout() {
        val g = tr()
        val base = inside("16:00").filter { it.ts.isBefore(at("14:00")) || !it.ts.isBefore(at("14:40")) }
        g.feedAll(base + listOf(smp("14:00", 500.0), smp("14:20", 600.0), smp("16:05", 500.0), smp("16:16", 800.0)))
        assertEquals(at("16:00"), g.finalize().first); assertTrue(g.notes.any { "근무중 이탈" in it })
    }
    @Test fun left1458CountsAsEnd() { val g = tr(); g.feedAll(inside("14:55") + listOf(smp("14:58", 20.0), smp("15:02", 300.0), smp("15:13", 600.0))); assertEquals(at("15:00"), g.finalize().first) }
    @Test fun earlyLeaveFlag() { val g = tr(); g.feedAll(inside("13:00") + listOf(smp("13:10", 3000.0), smp("15:10", 5000.0), smp("15:20", 5000.0))); assertEquals(at("13:00"), g.finalize().first); assertTrue(g.notes.any { "정시 전" in it }) }
    @Test fun earlyLeavePhoneDies() { val g = tr(); g.feedAll(inside("13:00") + smp("13:05", 3000.0)); assertEquals(at("13:00") to Source.NEEDS_CHECK, g.finalize()) }
    @Test fun noData() = assertEquals(at("15:00") to Source.DEFAULT, tr().finalize())
    @Test fun phoneDiesAfterExit() { val g = tr(); g.feedAll(inside("16:30") + smp("16:33", 400.0)); assertEquals(at("16:30") to Source.AUTO, g.finalize()) }
    @Test fun neverLeft() { val g = tr(); g.feedAll(inside("20:00")); assertEquals(at("20:00") to Source.NEEDS_CHECK, g.finalize()) }
    @Test fun boundaryIgnored() { val g = tr(); g.feed(smp("15:30", 95.0, 30.0)); assertEquals(1, g.ignored) }
    @Test fun haversine() { val (la, lo) = offsetPoint(WL.first, WL.second, 100.0); assertTrue(kotlin.math.abs(haversineM(WL.first, WL.second, la, lo) - 100) < 0.5) }
    @Test fun liveOvertime() { val g = tr(); g.feedAll(inside("16:20")); assertEquals(80, g.liveOvertimeMin(at("16:20"))); assertEquals(0, g.liveOvertimeMin(at("14:00"))) }

    // ── 공제
    @Test fun insurance2026() {
        val p = calcPayroll(c, s, OCT, buildMonth(c, OCT, emptyMap(), NOV1), NOV1)
        val tx = 2_256_880
        assertEquals(tx, p.taxable)
        assertEquals((tx * 0.0475).toInt() / 10 * 10, p.pension)
        assertEquals((tx * 0.03595).toInt() / 10 * 10, p.health)
        assertEquals((p.health * 0.1314).toInt() / 10 * 10, p.ltc)
        assertEquals((tx * 0.009).toInt() / 10 * 10, p.employment)
        assertTrue(p.incomeTax in 10_001..39_999)
        assertEquals(p.incomeTax / 100 * 10, p.localTax)
        assertEquals(p.gross - p.totalDeduct, p.net)
        assertEquals(2_010_850, p.net) // PC(파이썬) 버전과 동일
    }
    @Test fun dependentsLowerTax() = assertTrue(incomeTaxEstimate(30e6, 1.4e6, 3) < incomeTaxEstimate(30e6, 1.4e6, 1))
    @Test fun taxOverride() {
        val p = calcPayroll(c, s.copy(incomeTaxOverride = 12_340, nontaxableMonthly = 200_000), OCT, buildMonth(c, OCT, emptyMap(), NOV1), NOV1)
        assertEquals(12_340, p.incomeTax); assertEquals(1_230, p.localTax); assertEquals(p.gross - 200_000, p.taxable)
    }
    @Test fun warning2027() = assertTrue(calcPayroll(c, s, YearMonth.of(2027, 1), emptyList(), LocalDate.of(2027, 1, 5)).warnings.any { "2027" in it })

    // ── 홈 요약
    @Test fun summary() {
        val recs = mapOf(
            LocalDate.of(2026, 10, 12) to DayRecord(LocalDate.of(2026, 10, 12), 1, Status.WORK, hm("06:00"), hm("16:00")),
            LocalDate.of(2026, 10, 13) to DayRecord(LocalDate.of(2026, 10, 13), 1, Status.WORK, hm("06:00"), hm("17:00")),
            LocalDate.of(2026, 10, 2) to DayRecord(LocalDate.of(2026, 10, 2), 1, Status.WORK, hm("06:00"), hm("15:30")),
        )
        val sm = overtimeSummary(c, s, D, recs, liveTodayMin = 75)
        assertEquals(60, sm.todayMin)                // 75분 → 30분 단위 절사
        assertEquals(60 + 120 + 60, sm.weekMin)
        assertEquals(30 + 60 + 120 + 60, sm.monthMin)
        assertEquals(20_640, (120 / 60.0 * 10320).toInt()); assertEquals(sm.weekMin / 60.0 * 10320, sm.weekPay.toDouble(), 1.0)
        assertEquals(8, sm.workdaysSoFar); assertEquals(20, sm.workdaysTotal)
    }
}
