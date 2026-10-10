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
    @Test fun noLiveOvertimeOnHoliday() {
        val hol = LocalDate.of(2026, 10, 9) // 한글날
        val sm = overtimeSummary(c, s, hol, emptyMap(), liveTodayMin = 180)
        assertEquals(0, sm.todayMin); assertEquals(0, sm.todayPay)
        val absent = overtimeSummary(c, s, D, mapOf(D to DayRecord(D, 1, Status.LEAVE)), liveTodayMin = 120)
        assertEquals(0, absent.todayMin)
    }

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

    // ── 급여 산정기간·급여일
    @Test fun periodCalendarMonth() {
        val p = periodOf(c, OCT)
        assertEquals(LocalDate.of(2026, 10, 1), p.start); assertEquals(LocalDate.of(2026, 10, 31), p.end)
        assertEquals(LocalDate.of(2026, 11, 10), p.payDate) // 다음 달 10일 (화)
    }
    @Test fun period21to20() {
        val cc = c.copy(periodStartDay = 21, payDay = 25, payMonthOffset = 0)
        val p = periodOf(cc, OCT)
        assertEquals(LocalDate.of(2026, 9, 21), p.start); assertEquals(LocalDate.of(2026, 10, 20), p.end)
        assertEquals(OCT, periodContaining(cc, LocalDate.of(2026, 10, 20)).label)
        assertEquals(YearMonth.of(2026, 11), periodContaining(cc, LocalDate.of(2026, 10, 21)).label)
        assertEquals(LocalDate.of(2026, 10, 23), p.payDate) // 10/25 일요일 → 앞당겨 금요일 23일
        assertEquals(LocalDate.of(2026, 10, 26), periodOf(cc.copy(payAdjust = "after"), OCT).payDate)
        assertEquals(LocalDate.of(2026, 10, 25), periodOf(cc.copy(payAdjust = "none"), OCT).payDate)
        // 연말 걸친 기간 12/21~1/20
        val jan = periodOf(cc, YearMonth.of(2027, 1))
        assertEquals(LocalDate.of(2026, 12, 21), jan.start); assertEquals(31, jan.days.size)
    }
    @Test fun payDayEndOfMonthAndHoliday() {
        assertEquals(LocalDate.of(2026, 10, 30), periodOf(c.copy(payDay = 0, payMonthOffset = 0), OCT).payDate) // 10/31 토 → 30 금
        assertEquals(LocalDate.of(2026, 10, 8), periodOf(c.copy(payDay = 9, payMonthOffset = 0), OCT).payDate)  // 한글날 → 8일
    }
    @Test fun nextPaydayPicksNearest() {
        val np = nextPayday(c, D) // 10/14 → 9월분 지급일 10/10(토→10/9 휴일→10/8)은 지났고 10월분 11/10
        assertEquals(LocalDate.of(2026, 11, 10), np.payDate); assertEquals(OCT, np.label)
        assertEquals(LocalDate.of(2026, 10, 8), nextPayday(c, LocalDate.of(2026, 10, 8)).payDate)
    }
    @Test fun payroll21to20WithOvertime() {
        val cc = c.copy(periodStartDay = 21)
        val p = periodOf(cc, OCT)
        val d = LocalDate.of(2026, 9, 24) // 기간 안 (전월)
        val recs = buildPeriod(cc, p, mapOf(d to DayRecord(d, 1, Status.WORK, hm("06:00"), hm("17:00"))), NOV1)
        assertEquals(30, recs.size)
        assertEquals(20_640, calcPayroll(cc, s, p, recs, NOV1).overtimePay)
    }

    // ── 중도 입사
    @Test fun midPeriodHireCalendarAndWorkdays() {
        val hire = c.copy(startDate = LocalDate.of(2026, 10, 15))
        val p1 = calcPayroll(hire, s, periodOf(hire, OCT), buildPeriod(hire, periodOf(hire, OCT), emptyMap(), NOV1), NOV1)
        assertEquals((2_156_880L * 17 / 31).toInt(), p1.base)
        assertEquals(0, p1.bonus); assertEquals(0, p1.pension); assertEquals(0, p1.health)   // 1일 입사 아님
        assertTrue(p1.employment > 0)
        val wd = hire.copy(prorateBy = "workdays") // 10월 근무일 20일 중 15일 이후 12일
        val p2 = calcPayroll(wd, s, periodOf(wd, OCT), buildPeriod(wd, periodOf(wd, OCT), emptyMap(), NOV1), NOV1)
        assertEquals((2_156_880L * 12 / 20).toInt(), p2.base)
        val bon = hire.copy(bonusOnPartial = true)
        assertEquals(100_000, calcPayroll(bon, s, periodOf(bon, OCT), buildPeriod(bon, periodOf(bon, OCT), emptyMap(), NOV1), NOV1).bonus)
        val first = c.copy(startDate = LocalDate.of(2026, 10, 1))
        assertTrue(calcPayroll(first, s, periodOf(first, OCT), emptyList(), NOV1).pension > 0) // 1일 입사는 부과
    }

    // ── 연차·반차
    @Test fun leaveAutoAndManual() {
        val hired = c.copy(startDate = LocalDate.of(2024, 3, 4))
        val recs = listOf(DayRecord(LocalDate.of(2026, 5, 6), 1, Status.LEAVE), DayRecord(LocalDate.of(2026, 9, 1), 1, Status.HALF_LEAVE),
            DayRecord(LocalDate.of(2026, 12, 24), 1, Status.LEAVE), DayRecord(LocalDate.of(2026, 1, 2), 1, Status.LEAVE))
        val ls = leaveSummary(hired, D, recs) // 입사일 기준 2026-03-04~2027-03-03, 근속 2년 → 15일
        assertEquals(LocalDate.of(2026, 3, 4), ls.yearStart); assertEquals(15.0, ls.entitled, 0.0)
        assertEquals(1.5, ls.used, 0.0); assertEquals(1.0, ls.planned, 0.0); assertEquals(12.5, ls.remaining, 0.0)
        val manual = leaveSummary(hired.copy(annualLeaveDays = 18, leaveCarryover = 2.0, leaveBasis = "calendar"), D, recs)
        assertEquals(LocalDate.of(2026, 1, 1), manual.yearStart); assertEquals(20.0, manual.total, 0.0); assertEquals(2.5, manual.used, 0.0)
        val newbie = leaveSummary(c.copy(startDate = LocalDate.of(2026, 6, 1)), D, emptyList())
        assertTrue(newbie.firstYear); assertEquals(4.0, newbie.entitled, 0.0)
        val senior = leaveSummary(c.copy(startDate = LocalDate.of(2015, 1, 5)), D, emptyList())
        assertEquals(20.0, senior.entitled, 0.0) // 11년차: 15 + (11-1)/2
    }
    @Test fun halfLeaveNoEarlyLeaveNoOvertime() {
        val r = DayRecord(D, 1, Status.HALF_LEAVE, hm("06:00"), hm("10:00"))
        assertEquals(0, earlyLeaveMin(c, r)); assertEquals(0, overtimeMin(c, r))
        val p = calcPayroll(c, s, OCT, buildMonth(c, OCT, mapOf(D to r), NOV1), NOV1)
        assertEquals(100_000, p.bonus)
    }

    // ── 휴일 모드 퇴근 판정 (토요일 12:54 퇴근이 감지 안 되던 버그)
    @Test fun holidayCheckoutBeforeWorkEnd() {
        val sat = LocalDate.of(2026, 10, 10)
        fun s2(t: String, dist: Double): LocSample { val (la, lo) = offsetPoint(WL.first, WL.second, dist); return LocSample(sat.atTime(hm(t)), la, lo) }
        val g = GeofenceTracker(c, s, sat, holiday = true)
        g.feedAll(listOf(s2("06:51", 20.0), s2("12:54", 20.0), s2("12:58", 600.0), s2("13:01", 2000.0)))
        assertEquals(sat.atTime(12, 54), g.checkout)
        val normal = GeofenceTracker(c, s, sat) // 기존 방식이면 15시 전이라 미확정
        normal.feedAll(listOf(s2("06:51", 20.0), s2("12:54", 20.0), s2("12:58", 600.0), s2("13:01", 2000.0)))
        assertNull(normal.checkout)
        val still = GeofenceTracker(c, s, sat, holiday = true); still.feedAll(listOf(s2("06:51", 20.0), s2("12:54", 20.0)))
        assertEquals(sat.atTime(12, 54) to Source.NEEDS_CHECK, still.finalize())
    }

    // ── 퇴근 시간대 학습
    @Test fun learning() {
        assertNull(learnCheckoutWindow(c, listOf(hm("17:00"), hm("17:10"))))
        val w = learnCheckoutWindow(c, listOf("16:50", "17:00", "17:05", "17:00", "16:55", "17:20", "17:00").map { hm(it) })!!
        assertEquals(hm("16:35"), w.from); assertEquals(hm("17:50"), w.to); assertEquals(7, w.samples)
        assertEquals(10, pollIntervalMin(hm("17:00"), w))                // 기본: 촘촘히 끔 → 10분
        assertEquals(3, pollIntervalMin(hm("17:00"), w, 10, 3))          // 켜면 학습 시간대 3분
        assertEquals(10, pollIntervalMin(hm("15:30"), w, 10, 3)); assertEquals(10, pollIntervalMin(hm("18:30"), w, 10, 3))
        assertEquals(10, pollIntervalMin(hm("17:00"), null, 10, 3))
        assertEquals(10, AppSettings().pollAfterEndMin); assertEquals(30, AppSettings().holidayPollMin)
        assertEquals(hm("14:55"), trackingStart(c, w))
        val early = learnCheckoutWindow(c, List(6) { hm("14:30") })!!
        assertEquals(hm("14:15"), trackingStart(c, early)); assertEquals(hm("15:00"), early.to)
    }
}
