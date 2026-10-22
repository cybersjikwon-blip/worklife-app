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

    // ── 10/12 실제 버그: 16:50 퇴근인데 10:50으로 기록 (위치 기록 공백)
    private fun mon(t: String, dist: Double, kind: String = "fix"): LocSample {
        val (la, lo) = offsetPoint(WL.first, WL.second, dist); return LocSample(LocalDate.of(2026, 10, 12).atTime(hm(t)), la, lo, 10.0, kind)
    }
    private fun monAt(t: String) = LocalDate.of(2026, 10, 12).atTime(hm(t))
    @Test fun sparseGeofenceExit() {   // 출입 알림(exit) 16:51 + 2분 뒤 재확인
        val g = GeofenceTracker(c, s, LocalDate.of(2026, 10, 12))
        g.feedAll(listOf(mon("05:58", 30.0, "enter"), mon("10:50", 20.0), mon("16:51", 180.0, "exit"), mon("16:53", 1500.0)))
        assertEquals(monAt("16:50"), g.checkout); assertTrue(g.estimated); assertTrue(!g.uncertain)
    }
    @Test fun sparseOldDataNoKind() {  // 이미 저장된 기록(종류 표시 없음)도 16시대로, 단 확인필요
        val g = GeofenceTracker(c, s, LocalDate.of(2026, 10, 12))
        g.feedAll(listOf(mon("05:58", 30.0), mon("10:50", 20.0), mon("16:51", 180.0), mon("16:53", 1500.0)))
        assertEquals(monAt("16:50"), g.checkout); assertTrue(g.uncertain)
    }
    @Test fun sparsePollFarAway() {    // 10분 확인에서 3km 떨어진 곳에서 처음 잡힘 → 이동시간 6분 빼서 추정
        val g = GeofenceTracker(c, s, LocalDate.of(2026, 10, 12))
        g.feedAll(listOf(mon("06:00", 20.0), mon("14:55", 20.0), mon("16:56", 3000.0), mon("17:06", 6000.0)))
        assertEquals(monAt("16:50"), g.checkout)
    }
    @Test fun densePollingUsesLastInside() {  // 10분 간격으로 잘 들어오면 기존처럼 마지막 회사 확인 시각
        val g = GeofenceTracker(c, s, LocalDate.of(2026, 10, 12))
        g.feedAll(listOf(mon("06:00", 20.0), mon("16:35", 20.0), mon("16:45", 20.0), mon("16:55", 900.0), mon("17:05", 3000.0)))
        assertEquals(monAt("16:45"), g.checkout); assertTrue(!g.estimated)
    }
    @Test fun sparseFinalizeAfterPhoneOff() {   // 이탈 1건 후 폰 꺼짐 → 마감 때도 추정 시각
        val g = GeofenceTracker(c, s, LocalDate.of(2026, 10, 12))
        g.feedAll(listOf(mon("06:00", 20.0), mon("10:50", 20.0), mon("16:51", 180.0, "exit")))
        assertEquals(monAt("16:50") to Source.AUTO, g.finalize())
    }
    @Test fun earlyLeaveStillFlagged() {  // 정말 일찍 나간 경우는 여전히 조퇴 의심
        val g = GeofenceTracker(c, s, LocalDate.of(2026, 10, 12))
        g.feedAll(listOf(mon("06:00", 20.0), mon("11:00", 20.0), mon("11:05", 300.0, "exit"), mon("15:10", 3000.0), mon("15:20", 3000.0)))
        assertEquals(monAt("11:00"), g.checkout); assertTrue(g.notes.any { "정시 전" in it })
    }

    // ── 09: 점심 외출
    @Test fun lunchOutingWithinWindow() {
        val g = GeofenceTracker(c, s, LocalDate.of(2026, 10, 12))
        g.feedAll(listOf(mon("06:00", 20.0), mon("12:05", 300.0, "exit"), mon("12:48", 30.0, "enter"), mon("15:00", 20.0)))
        assertEquals(1, g.outings.size); assertTrue(g.outings[0].lunch); assertEquals(0, g.lunchOverMin)
        assertTrue(g.notes.isEmpty())                       // 경고·메모 없음
        assertNull(g.checkout)                               // 퇴근 아님
    }
    @Test fun lunchOverrun() {
        val g = GeofenceTracker(c, s, LocalDate.of(2026, 10, 12))
        g.feedAll(listOf(mon("06:00", 20.0), mon("12:10", 300.0, "exit"), mon("13:20", 30.0, "enter")))
        assertEquals(20, g.lunchOverMin); assertTrue(g.notes.any { "20분 초과" in it })
    }
    @Test fun nonLunchOutingStillNoted() {
        val g = GeofenceTracker(c, s, LocalDate.of(2026, 10, 12))
        g.feedAll(listOf(mon("06:00", 20.0), mon("09:00", 300.0, "exit"), mon("09:40", 30.0, "enter")))
        assertTrue(!g.outings[0].lunch); assertTrue(g.notes.any { "근무중 이탈" in it })
    }
    @Test fun outsideAfterLunchDetectable() {
        val g = GeofenceTracker(c, s, LocalDate.of(2026, 10, 12))
        g.feedAll(listOf(mon("06:00", 20.0), mon("12:10", 2000.0, "exit")))
        assertEquals(monAt("12:10"), g.outsideSince); assertNull(g.checkout)
    }
    @Test fun lunchOverDeductOption() {
        val d = LocalDate.of(2026, 10, 12)
        val r = DayRecord(d, 1, Status.WORK, hm("06:00"), hm("15:00"), Source.AUTO, "", lunchOverMin = 30)
        val off = calcPayroll(c, s, OCT, listOf(r), NOV1)
        assertEquals(0, off.lunchOverDeduct)
        val on = calcPayroll(c.copy(lunchOverDeduct = true), s, OCT, listOf(r), NOV1)
        assertEquals(5160, on.lunchOverDeduct); assertEquals(off.gross - 5160, on.gross)
    }
    @Test fun fastExitAuto() {
        assertEquals(1000.0, s.fastExitFor(c), 0.0)
        assertEquals(2000.0, s.fastExitFor(c.copy(radiusM = 200.0)), 0.0)
        assertEquals(1500.0, s.copy(fastExitM = 1500.0).fastExitFor(c), 0.0)
    }

    // ── 10: 퇴직금
    @Test fun severance() {
        val cc = c.copy(startDate = LocalDate.of(2024, 10, 17))
        val last = LocalDate.of(2026, 10, 16)
        assertNull(severanceEstimate(c.copy(startDate = LocalDate.of(2026, 1, 1)), s, last, 6_000_000)) // 1년 미만
        val sv = severanceEstimate(cc, s, last, 3L * 3_000_000)!!
        assertEquals(730L, sv.tenureDays); assertEquals(92L, sv.days3m)   // 7/17~10/16
        assertTrue(!sv.usedOrdinary)
        assertEquals((9_000_000.0 / 92 * 30 * 730 / 365).toLong(), sv.amount)
        val withBonus = severanceEstimate(cc.copy(annualBonus = 1_200_000), s, last, 3L * 3_000_000)!!
        assertTrue(withBonus.amount > sv.amount)
        // 최저임금 수준: 3개월 평균임금(달력일 기준)이 통상일급(시급×8)보다 낮으면 통상임금으로 (근로기준법 제2조②)
        val low = severanceEstimate(cc, s, last, 3L * 2_256_880)!!
        assertTrue(low.usedOrdinary); assertEquals((10320.0 * 8 * 30 * 730 / 365).toLong(), low.amount)
    }

    // ── 10: 사다리 · 룰렛
    @Test fun ladderIsPermutation() {
        repeat(200) { seed ->
            val n = 2 + seed % 7
            val lad = makeLadder(n, rnd = kotlin.random.Random(seed))
            val ends = (0 until n).map { ladderPath(lad, it).second }
            assertEquals((0 until n).toSet(), ends.toSet())                    // 결과가 겹치지 않음
            lad.forEach { row -> row.zipWithNext().forEach { (a, b) -> assertTrue(!(a && b)) } } // 붙은 가로줄 없음
            (0 until n - 1).forEach { g -> assertTrue(lad.any { it[g] }) }     // 모든 칸에 가로줄
        }
    }
    @Test fun pickAvoidsRecent() {
        val items = listOf("김치찌개", "짜장면", "돈까스")
        repeat(50) { assertEquals("돈까스", pickAvoidingRecent(items, setOf("김치찌개", "짜장면"), kotlin.random.Random(it))) }
        assertTrue(pickAvoidingRecent(items, items.toSet()) in items)
        assertNull(pickAvoidingRecent(emptyList(), emptySet()))
    }

    // ── 11: 퇴근 놓침 대비
    @Test fun indoorPoorAccuracyCountsInside() {
        // 실내라 오차 120m인 위치만 있어도 회사 안으로 인정 → 16:50 퇴근 감지
        val g = tr()
        g.feedAll(listOf(smp("15:05", 40.0, 120.0), smp("16:40", 30.0, 140.0), smp("17:00", 3000.0, 80.0), smp("17:10", 5000.0, 300.0)))
        assertTrue(g.firstInside != null)
        assertTrue(g.checkout != null)
        assertTrue(!g.checkout!!.isBefore(at("16:40")) && g.checkout!!.isBefore(at("17:01")))
    }
    @Test fun roughFarSampleNotIgnored() {
        // 차 안에서 오차 400m 위치라도 8km면 확실히 밖 → 1회로 확정
        val g = tr()
        g.feedAll(inside("16:30") + smp("16:45", 8000.0, 400.0))
        assertTrue(g.checkout != null)
    }
    @Test fun noInsideTodayAsksCheck() {
        // 오늘 회사 확인 기록이 하나도 없이 정시 이후 멀리서만 잡힘 → 정시로 임시 + 확인 필요
        val g = tr()
        g.feedAll(listOf(smp("17:30", 6000.0, 20.0)))
        assertEquals(at("15:00"), g.checkout)
        assertTrue(g.uncertain && g.noInsideToday)
        assertEquals(Source.NEEDS_CHECK, g.finalize().second)
    }
    @Test fun noInsideBeforeEndIgnored() {
        val g = tr()
        g.feedAll(listOf(smp("05:00", 6000.0), smp("05:30", 6000.0)))
        assertNull(g.checkout)
    }
    @Test fun resumeAfterWrongCheckout() {
        // 15:20에 잘못 퇴근 확정 → 15:30 [퇴근 취소] → 17:00에 다시 나감 → 17:00 근처로 확정
        val list = inside("15:00") + listOf(smp("15:10", 3000.0), smp("15:25", 3000.0), smp("16:00", 20.0), smp("16:55", 20.0), smp("17:05", 3000.0), smp("17:16", 3000.0))
        val a = tr(); a.feedAll(list); assertTrue(a.checkout!!.isBefore(at("15:30")))
        val b = tr(); b.feedAll(list, resume = at("15:30"))
        assertTrue(b.checkout != null && !b.checkout!!.isBefore(at("16:55")))
    }

    // ── 12: 카드 문자
    private val R0 = LocalDateTime.of(2026, 10, 21, 18, 0)
    @Test fun bcMultiLine() {
        val t = CardParser.parse("15884000", "[Web발신]\nBC카드(1234)승인\n홍*동\n12,300원 일시불\n10/21 14:23\n스타벅스강남점\n누적1,234,560원", R0)!!
        assertEquals(12300L, t.amount); assertEquals("1234", t.card); assertEquals("홍*동", t.name)
        assertEquals(LocalDateTime.of(2026, 10, 21, 14, 23), t.ts); assertEquals("스타벅스강남점", t.merchant); assertTrue(t.isBc)
    }
    @Test fun bcOneLine() {
        val t = CardParser.parse("15884000", "[Web발신]\n비씨(1*2*)홍길동님 10/21 12:05 8,000원 일시불 김밥천국 누적 512,300원", R0)!!
        assertEquals(8000L, t.amount); assertEquals("1*2*", t.card); assertEquals("김밥천국", t.merchant)
    }
    @Test fun bcCancelNegative() {
        val t = CardParser.parse("15884000", "[Web발신]\nBC카드(1234)취소\n홍*동\n12,300원\n10/21 15:00\n스타벅스강남점", R0)!!
        assertEquals(-12300L, t.amount)
    }
    @Test fun nonCardRejected() {
        assertNull(CardParser.parse("15882100", "[Web발신]\n농협 입금 50,000원 10/21 잔액 1,000,000원", R0))
        assertNull(CardParser.parse("15884000", "BC카드(1234) 승인거절 한도초과 30,000원", R0))
        assertNull(CardParser.parse("01012345678", "오늘 저녁 3만원 보낼게", R0))
    }
    @Test fun installmentAndForeign() {
        val t = CardParser.parse("15884000", "BC카드(5678)승인 김*수 360,000원 03개월 10/20 19:10 하이마트", R0)!!
        assertEquals(3, t.installment); assertEquals("김*수", t.name); assertEquals(360000L, t.amount)
        val f = CardParser.parse("15884000", "[Web발신]\nBC카드(1234)승인\n홍*동\nUSD 12.50\n10/21 03:10\nAMAZON", R0)!!
        assertEquals(0L, f.amount); assertTrue(f.foreign.contains("12.50"))
    }
    @Test fun yearRollover() {
        val t = CardParser.parse("15884000", "BC카드(1234)승인 홍*동 5,000원 일시불 12/31 23:50 편의점", LocalDateTime.of(2027, 1, 1, 0, 5))!!
        assertEquals(2026, t.ts.year)
    }
    @Test fun cardSummaryPeople() {
        val ppl = listOf(CardPerson("나", "010-1111-2222", listOf("1234")), CardPerson("아내", "01033334444", listOf("5678")))
        val d = LocalDateTime.of(2026, 10, 10, 12, 0)
        val txs = listOf(
            CardTx(ts = d, amount = 10000, card = "1234", isBc = true),
            CardTx(ts = d, amount = -2000, card = "1234", isBc = true),
            CardTx(ts = d, amount = 30000, card = "9*9*", sender = "01033334444", isBc = true), // 아내 폰에서 전달된 문자
            CardTx(ts = d, amount = 7000, card = "4321", isBc = true),                          // 미지정 BC
            CardTx(ts = d, amount = 50000, card = "9999", isBc = false),                        // 다른 카드사
            CardTx(ts = d.minusMonths(1), amount = 99999, card = "1234", isBc = true))          // 기간 밖
        val sm = summarizeCards(txs, ppl, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), bcOnly = true)
        assertEquals(45000L, sm.total); assertEquals(8000L, sm.perPerson[0].first); assertEquals(30000L, sm.perPerson[1].first)
        assertEquals(7000L, sm.unassigned.first); assertEquals(50000L, sm.otherCards["9999"]); assertEquals(-2000L, sm.cancelTotal)
        assertEquals(95000L, summarizeCards(txs, ppl, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), bcOnly = false).total)
    }
    @Test fun cardDedupeAndRange() {
        val a = CardTx(ts = R0, amount = 5000, card = "1234", source = "sms", raw = "x")
        assertTrue(sameCardTx(a, a.copy(ts = R0.plusMinutes(1), source = "noti", raw = "y")))
        assertTrue(!sameCardTx(a, a.copy(ts = R0.plusMinutes(1), raw = "y")))   // 같은 출처 다른 문자 = 실제 2번 결제
        assertTrue(!sameCardTx(a, a.copy(amount = 5100, source = "noti")))
        val (s1, e1) = cardRange(CardPeriod("cycle", 14), LocalDate.of(2026, 10, 5))
        assertEquals(LocalDate.of(2026, 9, 14), s1); assertEquals(LocalDate.of(2026, 10, 13), e1)
        val (s2, e2) = cardRange(CardPeriod("custom", customStart = LocalDate.of(2026, 8, 3)), LocalDate.of(2026, 10, 21))
        assertEquals(LocalDate.of(2026, 8, 3), s2); assertEquals(LocalDate.of(2026, 10, 21), e2)
    }

    // ── 13: 문자 권한 없이 가져오기
    @Test fun pasteManyMessages() {
        val txt = "[Web발신]\nBC카드(1234)승인\n홍*동\n12,300원 일시불\n10/21 14:23\n스타벅스\n[Web발신]\nBC카드(1234)취소\n홍*동\n12,300원\n10/21 15:00\n스타벅스\n\n안녕하세요 오늘 회의 3시\n\n비씨(5678)김*수님 10/20 12:05 8,000원 일시불 김밥천국"
        val l = CardImport.parseText(txt, R0, CardParser.DEFAULT_KEYWORDS)
        assertEquals(3, l.size); assertEquals(8000L, l.sumOf { it.amount })
    }
    @Test fun csvImport() {
        val csv = "BC카드 이용내역\n이용일자,이용시간,카드번호,가맹점명,이용금액,할부,구분\n2026.10.01,12:30,9410-****-****-1234,\"맛집, 강남\",\"15,000\",일시불,승인\n2026.10.02,09:00,9410-****-****-1234,편의점,3000,,취소\n합계,,,,18000,,"
        val l = CardImport.fromRows(CardImport.csvRows(csv))
        assertEquals(2, l.size); assertEquals("1234", l[0].card); assertEquals("맛집, 강남", l[0].merchant)
        assertEquals(LocalDateTime.of(2026, 10, 1, 12, 30), l[0].ts); assertEquals(-3000L, l[1].amount)
    }
    @Test fun xlsxImport() {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("xl/sharedStrings.xml"))
            z.write("<sst><si><t>승인일시</t></si><si><t>가맹점</t></si><si><t>승인금액</t></si><si><t>GS25 &amp; 카페</t></si></sst>".toByteArray()); z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("xl/worksheets/sheet1.xml"))
            z.write(("<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c><c r=\"C1\" t=\"s\"><v>2</v></c></row>" +
                "<row r=\"2\"><c r=\"A2\"><v>46316.5</v></c><c r=\"B2\" t=\"s\"><v>3</v></c><c r=\"C2\"><v>4500</v></c></row></sheetData></worksheet>").toByteArray()); z.closeEntry()
        }
        val l = CardImport.fromRows(CardImport.rowsFromFile(bos.toByteArray()))
        assertEquals(1, l.size); assertEquals("GS25 & 카페", l[0].merchant); assertEquals(4500L, l[0].amount)
        assertEquals(LocalDateTime.of(2026, 10, 21, 12, 0), l[0].ts)
    }
    @Test fun htmlXlsImport() {
        val h = "<table><tr><td>이용일</td><td>이용하신곳</td><td>이용금액</td></tr><tr><td>2026-10-03</td><td>주유소</td><td>50,000</td></tr></table>"
        val l = CardImport.fromRows(CardImport.rowsFromFile(h.toByteArray()))
        assertEquals(1, l.size); assertEquals(50000L, l[0].amount)
    }
    @Test fun fileDedupesWithNoti() {
        val n = CardTx(ts = R0.withHour(14), amount = 12300, card = "1234", source = "sms", raw = "a")
        assertTrue(sameCardTx(n, n.copy(ts = R0.withHour(12), card = "", source = "file", raw = "b")))
        assertTrue(!sameCardTx(n, n.copy(ts = R0.withHour(12).minusDays(1), card = "", source = "file", raw = "b")))
    }
    @Test fun rcsCardScreenshot() {
        // 비씨카드 RCS 카드형 메시지 화면 캡처를 글자인식한 결과 (띄어쓰기·버튼 문구 섞임)
        val ocr = "비씨카드\n1588-4000\n[Web발신] 확인된 발신번호\n승인\nIBK기업BC(9955)\n54,900 원 사용\n법인\n일시불\n10/21 10:46\n네이버파이낸셜(주)\n오늘 지출 한눈에 보기\n카드 사용처 확인\n오전 10:46\n" +
            "[Web발신] 확인된 발신번호\n승인\nIBK기업BC(9955)\n3,600원 사용\n법인\n일시불\n10 / 21 12:00\nGS25초월용수점\n오늘 지출 한눈에 보기\n카드 사용처 확인\n오후 12:01\n입력할 수 없는 대화방입니다"
        val l = CardImport.parseText(ocr, R0, CardParser.DEFAULT_KEYWORDS, "image")
        assertEquals(2, l.size); assertEquals(58500L, l.sumOf { it.amount })
        assertEquals("네이버파이낸셜(주)", l[0].merchant); assertEquals("GS25초월용수점", l[1].merchant)
        assertEquals("9955", l[1].card); assertEquals(LocalDateTime.of(2026, 10, 21, 12, 0), l[1].ts); assertTrue(l.all { it.isBc })
        // 같은 결제가 이미 알림으로 들어와 있으면 중복 아님 처리
        assertTrue(sameCardTx(l[0], l[0].copy(source = "sms", raw = "x")))
    }
    @Test fun rcsNotificationShort() {
        // 카드형 메시지 알림: 제목(비씨카드) + 짧은 본문
        val a = CardParser.parse("", "비씨카드\nIBK기업BC(9955) 54,900원 사용", R0)!!
        assertEquals(54900L, a.amount); assertTrue(a.isBc)
        val b = CardParser.parse("", "비씨카드\n3,600원 사용", R0)!!
        assertEquals(3600L, b.amount); assertTrue(b.isBc)
        val c = CardParser.parse("15884000", "1588-4000\n12,000원 사용 일시불", R0)!!
        assertTrue(c.isBc)
    }
    @Test fun perCardPeriods() {
        val today = LocalDate.of(2026, 10, 22)
        val ppl = listOf(CardPerson("A", cards = listOf("1111")),                       // 공통(이번 달)
            CardPerson("B", cards = listOf("2222"), cycleDay = 14),                       // 9/14~ → 10/14~11/13
            CardPerson("C", cards = listOf("3333"), start = LocalDate.of(2026, 9, 20)))  // 9/20~오늘
        val common = CardPeriod("month")
        assertEquals(LocalDate.of(2026, 10, 14), personRange(ppl[1], common, today).first)
        val (sf, _) = cardSpan(common, ppl, today); assertEquals(LocalDate.of(2026, 9, 20), sf)
        fun t(d: Int, m: Int, a: Long, c: String) = CardTx(ts = LocalDate.of(2026, m, d).atTime(12, 0), amount = a, card = c, isBc = true)
        val txs = listOf(t(5, 10, 1000, "1111"), t(25, 9, 9000, "1111"),   // A: 10/5만
            t(10, 10, 2000, "2222"), t(15, 10, 3000, "2222"),               // B: 10/15만
            t(21, 9, 4000, "3333"), t(19, 9, 7000, "3333"))                 // C: 9/21만
        val sm = summarizeNow(txs, ppl, common, true, today)
        assertEquals(listOf(1000L, 3000L, 4000L), sm.perPerson.map { it.first }); assertEquals(8000L, sm.total)
    }
}
