package kr.worklife.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * 슬기로운 직장생활 — 핵심 로직 (안드로이드 의존성 없음 → JVM에서 단독 테스트 가능)
 */

const val MONTHLY_STD_HOURS = 209

val DEFAULT_MIN_WAGE: Map<Int, Int> = mapOf(2024 to 9860, 2025 to 10030, 2026 to 10320, 2027 to 10700)

data class InsRates(val pension: Double, val health: Double, val ltc: Double, val employment: Double)

val DEFAULT_INS: Map<Int, InsRates> = mapOf(
    2025 to InsRates(4.5, 3.545, 12.95, 0.9),
    2026 to InsRates(4.75, 3.595, 13.14, 0.9),
    2027 to InsRates(5.0, 3.595, 13.14, 0.9), // 국민연금만 확정, 건보·장기요양은 고시 전 임시값
)
val UNCONFIRMED_INS_YEARS = setOf(2027)

enum class Status(val label: String) { WORK("근무"), ABSENT("결근"), LEAVE("연차"), HALF_LEAVE("반차"), HOLIDAY("휴일"), HOLIDAY_WORK("휴일근무") }
enum class Source(val label: String) { AUTO("자동"), MANUAL("수동"), DEFAULT("기본"), NEEDS_CHECK("확인필요") }

fun floor10(x: Double): Int = if (x > 0) (floor(x / 10.0) * 10).toInt() else 0
fun hm(s: String): LocalTime = LocalTime.parse(s.trim().let { if (it.length == 4) "0$it" else it })
fun LocalTime.minOfDay(): Int = hour * 60 + minute
fun fmtHm(m: Int): String = "${m / 60}시간 ${m % 60}분"

/** 회사(직장)별 설정 — 이직하면 새 Company를 추가하고 이전 회사는 endDate로 마감 */
data class Company(
    val id: Long = 1,
    val name: String = "우리 회사",
    val startDate: LocalDate = LocalDate.of(2020, 1, 1),
    val endDate: LocalDate? = null,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val radiusM: Double = 100.0,
    val workStart: String = "06:00",
    val workEnd: String = "15:00",
    val lunchMin: Int = 60,
    val baseSalary: Int = 2_156_880,
    val baseLinkedToMinWage: Boolean = false,
    val overtimeRate: Double = 1.0,
    val overtimeUnitMin: Int = 30,
    val fullAttendanceBonus: Int = 100_000,
    val weekendDays: Set<Int> = setOf(6, 7), // ISO: 1=월 … 7=일
    val extraHolidays: Set<String> = emptySet(),
    val extraWorkdays: Set<String> = emptySet(),
    // ── 급여 산정기간·급여일
    val periodStartDay: Int = 1,        // 1 = 매월 1일~말일, 21 = 전월 21일~당월 20일 (최대 28)
    val payDay: Int = 10,               // 0 = 말일
    val payMonthOffset: Int = 1,        // 0 = 같은 달, 1 = 다음 달 지급 (기간이 끝나는 달 기준)
    val payAdjust: String = "before",   // 급여일이 휴일이면: before 앞당김 / after 미룸 / none 그대로
    // ── 중도 입·퇴사
    val prorateBy: String = "calendar", // calendar 달력일수 / workdays 근무일수
    val bonusOnPartial: Boolean = false,// 중도 입·퇴사 기간에도 개근이면 만근수당 지급
    // ── 연차
    val annualLeaveDays: Int = -1,      // -1 = 근로기준법 자동 계산
    val leaveBasis: String = "hire",    // hire 입사일 기준 / calendar 1월 1일 기준
    val leaveCarryover: Double = 0.0,   // 이월 연차
) {
    val hasLocation get() = lat != 0.0 || lon != 0.0
    fun activeOn(d: LocalDate) = !d.isBefore(startDate) && (endDate == null || !d.isAfter(endDate))
    fun scheduledMin() = hm(workEnd).minOfDay() - hm(workStart).minOfDay() - lunchMin
}

/** 개인(공통) 설정 */
data class AppSettings(
    val minWageTable: Map<Int, Int> = DEFAULT_MIN_WAGE,
    val minWageOverride: Int = 0,
    val hourlyBasis: String = "min_wage", // or "base_div_209"
    val holidayWorkRate: Double = 1.5,
    val leaveCountsAsAttendance: Boolean = true,
    val deductAbsence: Boolean = true,
    val deductEarlyLeave: Boolean = false,
    val exitConfirmMin: Int = 10,
    val exitConfirmSamples: Int = 2,
    val fastExitM: Double = 1000.0,
    val maxAccuracyM: Double = 50.0,
    val dayCutoff: String = "23:59",
    val dependents: Int = 1,
    val children820: Int = 0,
    val withholdingRatio: Int = 100,
    val nontaxableMonthly: Int = 0,
    val insuranceBaseOverride: Int = 0,
    val pensionFloor: Int = 400_000,
    val pensionCap: Int = 6_370_000,
    val insOverride: Map<Int, InsRates> = emptyMap(),
    val incomeTaxOverride: Int = -1,
    val weeklyOvertimeLimitMin: Int = 12 * 60,
) {
    fun minWage(year: Int): Int {
        if (minWageOverride > 0) return minWageOverride
        val t = minWageTable.ifEmpty { DEFAULT_MIN_WAGE }
        t[year]?.let { return it }
        val older = t.keys.filter { it <= year }
        return t[older.maxOrNull() ?: t.keys.min()]!!
    }

    fun baseFor(c: Company, year: Int) = if (c.baseLinkedToMinWage) minWage(year) * MONTHLY_STD_HOURS else c.baseSalary
    fun hourly(c: Company, year: Int): Double =
        if (hourlyBasis == "base_div_209") baseFor(c, year).toDouble() / MONTHLY_STD_HOURS else minWage(year).toDouble()

    fun insRates(year: Int): InsRates {
        insOverride[year]?.let { return it }
        DEFAULT_INS[year]?.let { return it }
        val ks = DEFAULT_INS.keys.sorted()
        return DEFAULT_INS[ks.lastOrNull { it <= year } ?: ks.first()]!!
    }
}

// ─────────────────────────── 휴일 ───────────────────────────
class HolidayCalendar(private val company: Company, private val table: Map<String, String> = HolidayTable.map) {
    fun holidayName(d: LocalDate): String? {
        val iso = d.toString()
        if (iso in company.extraWorkdays) return null
        if (iso in company.extraHolidays) return "회사 휴무"
        table[iso]?.let { return it }
        if (d.dayOfWeek.value in company.weekendDays) return if (d.dayOfWeek == DayOfWeek.SUNDAY) "일요일" else if (d.dayOfWeek == DayOfWeek.SATURDAY) "토요일" else "휴무요일"
        return null
    }

    fun isWorkday(d: LocalDate) = holidayName(d) == null
    fun workdays(ym: YearMonth) = monthDays(ym).filter { isWorkday(it) && company.activeOn(it) }

    companion object {
        fun covered(year: Int) = year in HolidayTable.FIRST_YEAR..HolidayTable.LAST_YEAR
    }
}

fun monthDays(ym: YearMonth): List<LocalDate> = (1..ym.lengthOfMonth()).map { ym.atDay(it) }

// ─────────────────────────── 지오펜스 ───────────────────────────
data class LocSample(val ts: LocalDateTime, val lat: Double, val lon: Double, val accuracy: Double = 10.0)

fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
    val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
    val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 2 * r * asin(sqrt(min(1.0, a)))
}

fun offsetPoint(lat: Double, lon: Double, northM: Double = 0.0, eastM: Double = 0.0): Pair<Double, Double> {
    val dLat = northM / 111_320.0
    val dLon = eastM / (111_320.0 * cos(Math.toRadians(lat)))
    return Pair(lat + dLat, lon + dLon)
}

/**
 * 하루치 위치 샘플 → 퇴근 판정.
 *  - 퇴근시각 = 마지막으로 반경 안에 있던 시각 (감지 지연 보정)
 *  - 반경 밖 exitConfirmMin분 + 연속 n회 이상, 또는 fastExitM 이상 멀어지면 확정
 *  - 다시 들어오면 취소, 정시 전 이탈은 퇴근 아님(메모만)
 *  - 오차 큰 샘플/경계의 애매한 샘플 무시
 */
class GeofenceTracker(private val c: Company, private val s: AppSettings, val day: LocalDate) {
    val endDt: LocalDateTime = day.atTime(hm(c.workEnd))
    var firstInside: LocalDateTime? = null; private set
    var lastInside: LocalDateTime? = null; private set
    var checkout: LocalDateTime? = null; private set
    var ignored = 0; private set
    val notes = mutableListOf<String>()
    private var outSince: LocalDateTime? = null
    private var outCount = 0

    fun distance(smp: LocSample) = haversineM(c.lat, c.lon, smp.lat, smp.lon)

    private fun classify(smp: LocSample): Boolean? {
        if (smp.accuracy > s.maxAccuracyM) return null
        val d = distance(smp)
        if (d + smp.accuracy <= c.radiusM || d <= c.radiusM * 0.5) return true
        if (d - smp.accuracy > c.radiusM) return false
        return null
    }

    fun feed(smp: LocSample): LocalDateTime? {
        if (checkout != null || smp.ts.toLocalDate() != day) return checkout
        val inside = classify(smp) ?: run { ignored++; return null }
        if (inside) {
            if (firstInside == null) firstInside = smp.ts
            lastInside = smp.ts
            val os = outSince
            if (os != null && os.isBefore(endDt)) {
                val mins = ChronoUnit.MINUTES.between(os, smp.ts)
                if (mins >= s.exitConfirmMin) notes += "근무중 이탈 ${os.toLocalTime().hhmm()}~${smp.ts.toLocalTime().hhmm()} (${mins}분)"
            }
            outSince = null; outCount = 0
            return null
        }
        val li = lastInside ?: return null // 출근 전 이동중
        if (outSince == null) outSince = smp.ts
        outCount++
        val away = ChronoUnit.SECONDS.between(outSince, smp.ts) / 60.0
        val far = distance(smp) >= s.fastExitM
        if (!smp.ts.isBefore(endDt) && outCount >= s.exitConfirmSamples && (away >= s.exitConfirmMin || far)) {
            var co = li
            if (co.isBefore(endDt)) {
                if (ChronoUnit.MINUTES.between(co, endDt) > s.exitConfirmMin) notes += "정시 전 이탈 의심 (마지막 사업장 확인 ${co.toLocalTime().hhmm()})"
                else co = endDt
            }
            checkout = co
        }
        return checkout
    }

    fun feedAll(list: List<LocSample>): LocalDateTime? {
        list.sortedBy { it.ts }.forEach { feed(it) }
        return checkout
    }

    /** 하루 마감: (퇴근시각, 근거) */
    fun finalize(): Pair<LocalDateTime, Source> {
        checkout?.let { return it to Source.AUTO }
        val li = lastInside ?: run { notes += "위치 기록 없음 → 정시 퇴근 처리"; return endDt to Source.DEFAULT }
        if (outSince != null) {
            if (!li.isBefore(endDt)) return li to Source.AUTO
            if (ChronoUnit.MINUTES.between(li, endDt) <= s.exitConfirmMin) return endDt to Source.AUTO
            notes += "정시 전 이탈 후 기록 끊김 (마지막 사업장 ${li.toLocalTime().hhmm()}) → 조퇴 여부 확인 필요"
            return li to Source.NEEDS_CHECK
        }
        if (li.isBefore(endDt)) { notes += "정시 이후 위치 기록 없음 → 정시 퇴근 처리"; return endDt to Source.DEFAULT }
        notes += "반경 이탈 미감지 → 마지막 사업장 확인 시각으로 마감, 확인 필요"
        val cutoff = day.atTime(hm(s.dayCutoff))
        return (if (li.isAfter(cutoff)) cutoff else li) to Source.NEEDS_CHECK
    }

    /** 오늘 진행중 상태에서 '현재까지 연장(분)' 추정 — 홈 화면 실시간 표시용 */
    fun liveOvertimeMin(now: LocalDateTime): Int {
        if (checkout != null) return 0
        val li = lastInside ?: return 0
        if (now.isBefore(endDt) || ChronoUnit.MINUTES.between(li, now) > 30) return 0
        return ChronoUnit.MINUTES.between(endDt, now).toInt().coerceAtLeast(0)
    }
}

fun LocalTime.hhmm(): String = "%02d:%02d".format(hour, minute)

// ─────────────────────────── 근태 기록 ───────────────────────────
data class DayRecord(
    val day: LocalDate,
    val companyId: Long,
    val status: Status = Status.WORK,
    val checkIn: LocalTime? = null,
    val checkOut: LocalTime? = null,
    val source: Source = Source.DEFAULT,
    val note: String = "",
)

fun overtimeMin(c: Company, r: DayRecord): Int {
    if (r.status != Status.WORK || r.checkOut == null) return 0
    val diff = r.checkOut.minOfDay() - hm(c.workEnd).minOfDay()
    if (diff <= 0) return 0
    val u = max(1, c.overtimeUnitMin)
    return diff / u * u
}

fun earlyLeaveMin(c: Company, r: DayRecord): Int {
    if (r.status != Status.WORK || r.checkOut == null) return 0
    return max(0, hm(c.workEnd).minOfDay() - r.checkOut.minOfDay())
}

fun holidayWorkMin(c: Company, r: DayRecord): Int {
    if (r.status != Status.HOLIDAY_WORK || r.checkIn == null || r.checkOut == null) return 0
    var m = r.checkOut.minOfDay() - r.checkIn.minOfDay()
    m -= when { m >= 8 * 60 -> c.lunchMin; m >= 4 * 60 -> 30; else -> 0 }
    return max(0, m)
}

// ─────────────────────────── 급여 산정기간 ───────────────────────────
/** label = 기간이 끝나는 달 (예: 9/21~10/20 → 10월분) */
data class PayPeriod(val label: YearMonth, val start: LocalDate, val end: LocalDate, val payDate: LocalDate) {
    val days: List<LocalDate> get() = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
    fun contains(d: LocalDate) = !d.isBefore(start) && !d.isAfter(end)
    fun rangeText() = "${start.monthValue}/${start.dayOfMonth}~${end.monthValue}/${end.dayOfMonth}"
}

private fun Company.startDayFixed() = periodStartDay.coerceIn(1, 28)

fun periodOf(c: Company, label: YearMonth): PayPeriod {
    val sd = c.startDayFixed()
    val start = if (sd == 1) label.atDay(1) else label.minusMonths(1).atDay(sd)
    val end = if (sd == 1) label.atEndOfMonth() else label.atDay(sd - 1)
    return PayPeriod(label, start, end, payDateOf(c, label))
}

fun periodContaining(c: Company, d: LocalDate): PayPeriod {
    val sd = c.startDayFixed()
    val label = if (sd == 1 || d.dayOfMonth < sd) YearMonth.from(d) else YearMonth.from(d).plusMonths(1)
    return periodOf(c, label)
}

fun payDateOf(c: Company, label: YearMonth): LocalDate {
    val m = label.plusMonths(c.payMonthOffset.coerceIn(0, 2).toLong())
    var d = if (c.payDay <= 0 || c.payDay > m.lengthOfMonth()) m.atEndOfMonth() else m.atDay(c.payDay)
    val cal = HolidayCalendar(c)
    var guard = 0
    while (!cal.isWorkday(d) && c.payAdjust != "none" && guard++ < 14) d = if (c.payAdjust == "after") d.plusDays(1) else d.minusDays(1)
    return d
}

/** 오늘 이후(오늘 포함) 가장 가까운 급여일과 그 급여의 기간 */
fun nextPayday(c: Company, today: LocalDate): PayPeriod {
    val cur = periodContaining(c, today).label
    return (-3L..3L).map { periodOf(c, cur.plusMonths(it)) }.filter { !it.payDate.isBefore(today) }.minByOrNull { it.payDate }
        ?: periodOf(c, cur.plusMonths(1))
}

/** 기간 안의 날짜별 기록 (빈 날 자동 채움) */
fun buildPeriod(c: Company, p: PayPeriod, stored: Map<LocalDate, DayRecord>, today: LocalDate): List<DayRecord> =
    fill(c, p.days, stored, today)

/** 저장된 기록 + 빈 날 자동 채움. 오늘 이후 근무일은 퇴근=null(예정). 다른 회사 재직 기간은 제외 */
fun buildMonth(c: Company, ym: YearMonth, stored: Map<LocalDate, DayRecord>, today: LocalDate): List<DayRecord> =
    fill(c, monthDays(ym), stored, today)

private fun fill(c: Company, dates: List<LocalDate>, stored: Map<LocalDate, DayRecord>, today: LocalDate): List<DayRecord> {
    val cal = HolidayCalendar(c)
    return dates.filter { c.activeOn(it) }.map { d ->
        stored[d] ?: run {
            val h = cal.holidayName(d)
            when {
                h != null -> DayRecord(d, c.id, Status.HOLIDAY, note = h)
                d.isBefore(today) -> DayRecord(d, c.id, Status.WORK, hm(c.workStart), hm(c.workEnd), Source.DEFAULT)
                else -> DayRecord(d, c.id, Status.WORK, hm(c.workStart), null, Source.DEFAULT, "예정")
            }
        }
    }
}

// ─────────────────────────── 세금 ───────────────────────────
/** 근로소득 간이세액표 산식 근사 (연 결정세액) */
fun incomeTaxEstimate(annualPay: Double, annualPension: Double, dependents: Int): Int {
    val a = annualPay
    val ded = when {
        a <= 5_000_000 -> a * 0.7
        a <= 15_000_000 -> 3_500_000 + (a - 5_000_000) * 0.4
        a <= 45_000_000 -> 7_500_000 + (a - 15_000_000) * 0.15
        a <= 100_000_000 -> 12_000_000 + (a - 45_000_000) * 0.05
        else -> 14_750_000 + (a - 100_000_000) * 0.02
    }
    val income = a - ded
    val n = max(1, dependents)
    val personal = 1_500_000.0 * n
    val (base, r1, r2, r3) = when (n) {
        1 -> listOf(3_100_000.0, 0.04, 0.015, 0.005)
        2 -> listOf(3_600_000.0, 0.04, 0.02, 0.005)
        else -> listOf(5_000_000.0, 0.07, 0.05, 0.03)
    }
    var special = when {
        a <= 30_000_000 -> base + a * r1
        a <= 45_000_000 -> base + a * r1 - (a - 30_000_000) * 0.05
        a <= 70_000_000 -> base + a * r2
        else -> base + a * r3
    }
    if (n >= 3) special += max(0.0, a - 40_000_000) * 0.04
    val taxBase = max(0.0, income - personal - annualPension - special)
    val brackets = listOf(14e6 to 0.06, 50e6 to 0.15, 88e6 to 0.24, 150e6 to 0.35, 300e6 to 0.38,
        500e6 to 0.40, 1000e6 to 0.42, Double.MAX_VALUE to 0.45)
    var tax = 0.0; var prev = 0.0
    for ((lim, rate) in brackets) {
        if (taxBase > prev) tax += (min(taxBase, lim) - prev) * rate
        prev = lim
    }
    val credit = if (tax <= 1_300_000) tax * 0.55 else 715_000 + (tax - 1_300_000) * 0.30
    val limit = when {
        a <= 33_000_000 -> 740_000.0
        a <= 70_000_000 -> max(660_000.0, 740_000 - (a - 33_000_000) * 0.008)
        a <= 120_000_000 -> max(500_000.0, 660_000 - (a - 70_000_000) * 0.5)
        else -> max(200_000.0, 500_000 - (a - 120_000_000) * 0.5)
    }
    return max(0.0, tax - min(credit, limit)).toInt()
}

fun childCreditMonthly(ch: Int) = when {
    ch <= 0 -> 0
    ch == 1 -> 20_830
    ch == 2 -> 45_830
    else -> 45_830 + (ch - 2) * 33_330
}

// ─────────────────────────── 급여 ───────────────────────────
data class PayLine(val label: String, val amount: Int, val bold: Boolean = false)

data class Payslip(
    val ym: YearMonth, val companyId: Long, val companyName: String,
    val minWage: Int, val hourly: Double, val base: Int,
    val overtimeMin: Int, val overtimePay: Int, val holidayWorkMin: Int, val holidayWorkPay: Int,
    val bonus: Int, val bonusStatus: String, val absenceDays: Int, val absenceDeduct: Int,
    val earlyMin: Int, val earlyDeduct: Int, val gross: Int, val nontaxable: Int, val taxable: Int,
    val pension: Int, val health: Int, val ltc: Int, val employment: Int, val incomeTax: Int, val localTax: Int,
    val totalDeduct: Int, val net: Int, val warnings: List<String>,
) {
    fun lines(): List<PayLine> = buildList {
        add(PayLine("기본급", base))
        add(PayLine("연장수당 (${fmtHm(overtimeMin)})", overtimePay))
        if (holidayWorkPay > 0) add(PayLine("휴일근무수당 (${fmtHm(holidayWorkMin)})", holidayWorkPay))
        add(PayLine("만근수당 [$bonusStatus]", bonus))
        if (absenceDeduct > 0) add(PayLine("결근 공제 (${absenceDays}일)", -absenceDeduct))
        if (earlyDeduct > 0) add(PayLine("조퇴 공제 (${earlyMin}분)", -earlyDeduct))
        add(PayLine("지급 합계", gross, true))
        add(PayLine("국민연금", -pension)); add(PayLine("건강보험", -health)); add(PayLine("장기요양보험", -ltc))
        add(PayLine("고용보험", -employment)); add(PayLine("소득세", -incomeTax)); add(PayLine("지방소득세", -localTax))
        add(PayLine("공제 합계", -totalDeduct, true))
        add(PayLine("실수령액", net, true))
    }
}

fun calcPayroll(c: Company, s: AppSettings, ym: YearMonth, records: List<DayRecord>, today: LocalDate): Payslip =
    calcPayroll(c, s, periodOf(c, ym), records, today)

fun calcPayroll(c: Company, s: AppSettings, p: PayPeriod, records: List<DayRecord>, today: LocalDate): Payslip {
    val warn = mutableListOf<String>()
    val ym = p.label
    val y = ym.year
    val mw = s.minWage(y)
    val hourly = s.hourly(c, y)
    val fullBase = s.baseFor(c, y)
    if (!HolidayCalendar.covered(y)) warn += "${y}년 공휴일 표가 없습니다. 설정 > 회사 휴무일에 직접 추가하세요"
    if (fullBase < mw * MONTHLY_STD_HOURS) warn += "기본급이 최저임금 월환산(${"%,d".format(mw * MONTHLY_STD_HOURS)}원)보다 낮습니다"

    // 중도 입사/퇴사 → 기본급 일할 계산 (달력일수 또는 근무일수)
    val cal = HolidayCalendar(c)
    val days = p.days
    val activeDays = days.count { c.activeOn(it) }
    val partial = activeDays < days.size
    val base = if (!partial) fullBase else if (c.prorateBy == "workdays") {
        val wdAll = days.count { cal.isWorkday(it) }.coerceAtLeast(1)
        val wdAct = days.count { cal.isWorkday(it) && c.activeOn(it) }
        warn += "중도 입사/퇴사: 기본급 근무일 ${wdAct}/${wdAll}일 일할 계산"
        (fullBase.toLong() * wdAct / wdAll).toInt()
    } else {
        warn += "중도 입사/퇴사: 기본급 ${activeDays}/${days.size}일 일할 계산"
        (fullBase.toLong() * activeDays / days.size).toInt()
    }

    val recs = records.filter { it.companyId == c.id && c.activeOn(it.day) && p.contains(it.day) }
    // 연도가 바뀌는 기간(12/21~1/20)도 날짜별 그 해 시급으로 계산
    val ot = recs.sumOf { overtimeMin(c, it) }
    val otPay = recs.sumOf { (overtimeMin(c, it) / 60.0 * s.hourly(c, it.day.year) * c.overtimeRate) }.toInt()
    val hw = recs.sumOf { holidayWorkMin(c, it) }
    val hwPay = recs.sumOf { (holidayWorkMin(c, it) / 60.0 * s.hourly(c, it.day.year) * s.holidayWorkRate) }.toInt()

    val absentDays = recs.count { it.status == Status.ABSENT }
    val bonusBreakers = absentDays + if (s.leaveCountsAsAttendance) 0 else recs.count { it.status == Status.LEAVE || it.status == Status.HALF_LEAVE }
    val daily = (hourly * c.scheduledMin() / 60).toInt()
    val absDeduct = if (s.deductAbsence) daily * absentDays else 0
    val early = recs.sumOf { earlyLeaveMin(c, it) }
    val earlyDeduct = if (s.deductEarlyLeave) (early / 60.0 * hourly).toInt() else 0

    val (bonus, bstat) = when {
        bonusBreakers > 0 -> 0 to "결근 있음"
        partial && !c.bonusOnPartial -> 0 to "중도 입·퇴사"
        !today.isAfter(p.end) -> c.fullAttendanceBonus to "예정"
        else -> c.fullAttendanceBonus to "만근"
    }

    val gross = base + otPay + hwPay + bonus - absDeduct - earlyDeduct
    val nontax = min(s.nontaxableMonthly, max(0, gross))
    val taxable = gross - nontax

    val r = s.insRates(y)
    if (y in UNCONFIRMED_INS_YEARS && !s.insOverride.containsKey(y)) warn += "${y}년 건강·장기요양 요율은 고시 전 임시값입니다 (설정에서 수정)"
    val insBase = if (s.insuranceBaseOverride > 0) s.insuranceBaseOverride else taxable
    val pensBase = insBase.coerceIn(s.pensionFloor, s.pensionCap)
    // 입사월: 1일 입사가 아니면 국민연금·건강보험(장기요양)은 다음 달부터 부과
    val firstMonthNoIns = p.contains(c.startDate) && c.startDate.dayOfMonth != 1
    if (firstMonthNoIns) warn += "입사월(${c.startDate}): 국민연금·건강보험은 다음 달부터 부과돼요 (고용보험만 공제)"
    if (c.endDate != null && p.contains(c.endDate)) warn += "퇴사월: 건강보험은 퇴사 후 정산될 수 있어요"
    val pension = if (firstMonthNoIns) 0 else floor10(pensBase * r.pension / 100)
    val health = if (firstMonthNoIns) 0 else floor10(insBase * r.health / 100)
    val ltc = floor10(health * r.ltc / 100)
    val employment = floor10(taxable * r.employment / 100)

    val itax = if (s.incomeTaxOverride >= 0) s.incomeTaxOverride else {
        val annual = incomeTaxEstimate(taxable * 12.0, pension * 12.0, s.dependents)
        val m = floor10(annual / 12.0) - childCreditMonthly(s.children820)
        floor10(max(0, m) * s.withholdingRatio / 100.0)
    }
    val ltax = floor10(itax * 0.1)
    val total = pension + health + ltc + employment + itax + ltax
    val need = recs.filter { it.source == Source.NEEDS_CHECK }
    if (need.isNotEmpty()) warn += "퇴근 확인 필요: " + need.joinToString(", ") { "${it.day.monthValue}/${it.day.dayOfMonth}" }

    return Payslip(ym, c.id, c.name, mw, hourly, base, ot, otPay, hw, hwPay, bonus, bstat, absentDays, absDeduct,
        early, earlyDeduct, gross, nontax, taxable, pension, health, ltc, employment, itax, ltax, total, gross - total, warn)
}

// ─────────────────────────── 홈 화면 요약 ───────────────────────────
data class OtSummary(val todayMin: Int, val weekMin: Int, val monthMin: Int,
                     val todayPay: Int, val weekPay: Int, val monthPay: Int,
                     val weekLimitMin: Int, val attendedDays: Int, val workdaysSoFar: Int, val workdaysTotal: Int)

/** 이번 주(월~일)·이번 달·오늘 연장 합계. liveTodayMin은 퇴근 전 실시간 추정치 */
fun overtimeSummary(c: Company, s: AppSettings, today: LocalDate,
                    recordsByDay: Map<LocalDate, DayRecord>, liveTodayMin: Int = 0): OtSummary {
    val cal = HolidayCalendar(c)
    fun ot(d: LocalDate): Int {
        val r = recordsByDay[d]
        if (d == today && (r == null || r.checkOut == null)) {
            // 휴일·결근·연차인 날은 실시간 연장 추정 없음 (휴일근무는 별도 기록으로만 계산)
            if (!cal.isWorkday(d) || (r != null && r.status != Status.WORK)) return 0
            val u = max(1, c.overtimeUnitMin)
            return liveTodayMin / u * u
        }
        return if (r == null) 0 else overtimeMin(c, r)
    }
    val hourly = s.hourly(c, today.year)
    fun pay(m: Int) = (m / 60.0 * hourly * c.overtimeRate).toInt()
    val period = periodContaining(c, today)
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val week = (0L..6L).map { monday.plusDays(it) }.filter { !it.isAfter(today) && c.activeOn(it) }
    val month = period.days.filter { !it.isAfter(today) && c.activeOn(it) }
    val t = ot(today); val w = week.sumOf { ot(it) }; val m = month.sumOf { ot(it) }
    val wdAll = period.days.filter { cal.isWorkday(it) && c.activeOn(it) }
    val wdSoFar = wdAll.filter { !it.isAfter(today) }
    val attended = wdSoFar.count { d -> recordsByDay[d]?.status.let { it == null || it == Status.WORK || ((it == Status.LEAVE || it == Status.HALF_LEAVE) && s.leaveCountsAsAttendance) } }
    return OtSummary(t, w, m, pay(t), pay(w), pay(m), s.weeklyOvertimeLimitMin, attended, wdSoFar.size, wdAll.size)
}

// ─────────────────────────── 연차 ───────────────────────────
data class LeaveSummary(val entitled: Double, val carry: Double, val used: Double, val planned: Double,
                        val yearStart: LocalDate, val yearEnd: LocalDate, val auto: Boolean, val firstYear: Boolean) {
    val total get() = entitled + carry
    val remaining get() = total - used - planned
}

fun leaveWeight(r: DayRecord) = when (r.status) { Status.LEAVE -> 1.0; Status.HALF_LEAVE -> 0.5; else -> 0.0 }

/** 연차 기간(입사일 또는 1월 1일 기준)과 부여 일수. records = 해당 회사의 그 기간 기록 */
fun leaveWindow(c: Company, today: LocalDate): Pair<LocalDate, LocalDate> {
    if (c.leaveBasis == "calendar") return LocalDate.of(today.year, 1, 1) to LocalDate.of(today.year, 12, 31)
    var start = c.startDate
    while (!start.plusYears(1).isAfter(today)) start = start.plusYears(1)
    return start to start.plusYears(1).minusDays(1)
}

fun leaveSummary(c: Company, today: LocalDate, records: List<DayRecord>): LeaveSummary {
    val (ws, we) = leaveWindow(c, today)
    val tenureYears = ChronoUnit.YEARS.between(c.startDate, ws.coerceAtLeast(c.startDate)).toInt()
    val firstYear = today.isBefore(c.startDate.plusYears(1))
    val auto = c.annualLeaveDays < 0
    val entitled = if (!auto) c.annualLeaveDays.toDouble() else if (firstYear) {
        // 1년 미만: 1개월 개근마다 1일 (최대 11일) — 지난 달 수 기준
        min(11L, ChronoUnit.MONTHS.between(c.startDate, today)).toDouble()
    } else {
        val n = max(1, if (c.leaveBasis == "calendar") ChronoUnit.YEARS.between(c.startDate, ws).toInt().coerceAtLeast(1) else tenureYears)
        min(25, 15 + (n - 1) / 2).toDouble()
    }
    val inWin = records.filter { it.companyId == c.id && !it.day.isBefore(ws) && !it.day.isAfter(we) }
    val used = inWin.filter { !it.day.isAfter(today) }.sumOf { leaveWeight(it) }
    val planned = inWin.filter { it.day.isAfter(today) }.sumOf { leaveWeight(it) }
    return LeaveSummary(entitled, if (firstYear) 0.0 else c.leaveCarryover, used, planned, ws, we, auto, firstYear)
}
