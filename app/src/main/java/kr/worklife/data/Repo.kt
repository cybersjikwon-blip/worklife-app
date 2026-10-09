package kr.worklife.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kr.worklife.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

/** 급여 이력 한 줄 (확정 스냅샷 / 직접 입력 / 미확정 계산값) */
data class PayHistory(val ym: YearMonth, val companyId: Long, val companyName: String,
                      val gross: Int, val deduct: Int, val net: Int, val kind: String, val lines: List<PayLine>)

class Repo private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "worklife.db", null, 1) {
    private val prefs = ctx.getSharedPreferences("worklife", Context.MODE_PRIVATE)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE days(day TEXT, company INTEGER, status TEXT, ci TEXT, co TEXT, src TEXT, note TEXT, PRIMARY KEY(day, company))")
        db.execSQL("CREATE TABLE locs(ts TEXT, lat REAL, lon REAL, acc REAL)")
        db.execSQL("CREATE INDEX idx_locs ON locs(ts)")
        db.execSQL("CREATE TABLE payslips(ym TEXT, company INTEGER, json TEXT, kind TEXT, saved_at TEXT, PRIMARY KEY(ym, company))")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    // ───────── 설정 ─────────
    var settings: AppSettings
        get() = runCatching { settingsFromJson(JSONObject(prefs.getString("settings", "{}")!!)) }.getOrDefault(AppSettings())
        set(v) { prefs.edit().putString("settings", settingsToJson(v).toString()).apply() }

    var companies: List<Company>
        get() {
            val arr = runCatching { JSONArray(prefs.getString("companies", "[]")) }.getOrDefault(JSONArray())
            val list = (0 until arr.length()).mapNotNull { runCatching { companyFromJson(arr.getJSONObject(it)) }.getOrNull() }
            return list.ifEmpty { listOf(Company()) }.sortedBy { it.startDate }
        }
        set(v) { prefs.edit().putString("companies", JSONArray(v.map { companyToJson(it) }).toString()).apply() }

    fun saveCompany(c: Company) { companies = companies.filter { it.id != c.id } + c }

    /** 그 날짜에 재직 중인 회사 (겹치면 최근 입사) */
    fun companyOn(d: LocalDate): Company? = companies.filter { it.activeOn(d) }.maxByOrNull { it.startDate }
    fun currentCompany(): Company = companyOn(LocalDate.now()) ?: companies.last()
    fun companiesIn(ym: YearMonth) = companies.filter { c -> periodOf(c, ym).days.any { c.activeOn(it) } }

    /** 이직: 기존 회사 마감 + 새 회사(근무조건은 복사, 위치는 비움) */
    fun moveCompany(newName: String, start: LocalDate): Company {
        val cur = companyOn(start.minusDays(1)) ?: currentCompany()
        val list = companies.map { if (it.id == cur.id) it.copy(endDate = start.minusDays(1)) else it }
        val nc = cur.copy(id = (companies.maxOf { it.id }) + 1, name = newName, startDate = start, endDate = null,
            lat = 0.0, lon = 0.0, extraHolidays = emptySet(), extraWorkdays = emptySet())
        companies = list + nc
        return nc
    }

    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(v) { prefs.edit().putBoolean("onboarded", v).apply() }

    // ───────── 근태 ─────────
    fun saveDay(r: DayRecord) {
        writableDatabase.insertWithOnConflict("days", null, ContentValues().apply {
            put("day", r.day.toString()); put("company", r.companyId); put("status", r.status.name)
            put("ci", r.checkIn?.hhmm()); put("co", r.checkOut?.hhmm()); put("src", r.source.name); put("note", r.note)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteDay(d: LocalDate, companyId: Long) {
        writableDatabase.delete("days", "day=? AND company=?", arrayOf(d.toString(), companyId.toString()))
    }

    fun day(d: LocalDate, companyId: Long): DayRecord? = queryDays("day=? AND company=?", arrayOf(d.toString(), companyId.toString())).firstOrNull()

    fun month(ym: YearMonth, companyId: Long): Map<LocalDate, DayRecord> =
        queryDays("day LIKE ? AND company=?", arrayOf("$ym-%", companyId.toString())).associateBy { it.day }

    /** 기간 조회 (급여 산정기간이 달을 걸쳐도 OK) */
    fun range(start: LocalDate, end: LocalDate, companyId: Long): Map<LocalDate, DayRecord> =
        queryDays("day >= ? AND day <= ? AND company=?", arrayOf(start.toString(), end.toString(), companyId.toString())).associateBy { it.day }

    fun period(p: PayPeriod, companyId: Long) = range(p.start, p.end, companyId)

    fun firstRecordMonth(): YearMonth? = readableDatabase.rawQuery("SELECT MIN(day) FROM days", null).use {
        if (it.moveToFirst() && !it.isNull(0)) YearMonth.from(LocalDate.parse(it.getString(0))) else null
    }

    private fun queryDays(where: String, args: Array<String>): List<DayRecord> =
        readableDatabase.query("days", null, where, args, null, null, "day").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(DayRecord(LocalDate.parse(c.getString(0)), c.getLong(1),
                        runCatching { Status.valueOf(c.getString(2)) }.getOrDefault(Status.WORK),
                        c.getString(3)?.let { LocalTime.parse(it) }, c.getString(4)?.let { LocalTime.parse(it) },
                        runCatching { Source.valueOf(c.getString(5)) }.getOrDefault(Source.MANUAL), c.getString(6) ?: ""))
                }
            }
        }

    // ───────── 위치 ─────────
    fun addLoc(s: LocSample) {
        writableDatabase.insert("locs", null, ContentValues().apply {
            put("ts", s.ts.withNano(0).toString()); put("lat", s.lat); put("lon", s.lon); put("acc", s.accuracy)
        })
    }

    fun locsOf(d: LocalDate): List<LocSample> =
        readableDatabase.query("locs", null, "ts LIKE ?", arrayOf("$d%"), null, null, "ts").use { c ->
            buildList { while (c.moveToNext()) add(LocSample(LocalDateTime.parse(c.getString(0)), c.getDouble(1), c.getDouble(2), c.getDouble(3))) }
        }

    /** 위치 기록은 60일만 보관 (근태 기록은 영구) */
    fun pruneLocs() { writableDatabase.delete("locs", "ts < ?", arrayOf(LocalDate.now().minusDays(60).toString())) }

    // ───────── 급여 이력 ─────────
    fun savePayslip(p: Payslip) = savePay(p.ym, p.companyId, p.companyName, p.gross, p.totalDeduct, p.net, "확정", p.lines())

    fun savePay(ym: YearMonth, cid: Long, cname: String, gross: Int, deduct: Int, net: Int, kind: String, lines: List<PayLine>) {
        val j = JSONObject().put("company", cname).put("gross", gross).put("deduct", deduct).put("net", net)
            .put("lines", JSONArray(lines.map { JSONObject().put("l", it.label).put("a", it.amount).put("b", it.bold) }))
        writableDatabase.insertWithOnConflict("payslips", null, ContentValues().apply {
            put("ym", ym.toString()); put("company", cid); put("json", j.toString()); put("kind", kind)
            put("saved_at", LocalDateTime.now().withNano(0).toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deletePay(ym: YearMonth, cid: Long) { writableDatabase.delete("payslips", "ym=? AND company=?", arrayOf(ym.toString(), cid.toString())) }

    fun savedPays(): List<PayHistory> = readableDatabase.query("payslips", null, null, null, null, null, "ym DESC").use { c ->
        buildList {
            while (c.moveToNext()) runCatching {
                val j = JSONObject(c.getString(2))
                val la = j.getJSONArray("lines")
                add(PayHistory(YearMonth.parse(c.getString(0)), c.getLong(1), j.getString("company"), j.getInt("gross"),
                    j.getInt("deduct"), j.getInt("net"), c.getString(3),
                    (0 until la.length()).map { la.getJSONObject(it).let { o -> PayLine(o.getString("l"), o.getInt("a"), o.optBoolean("b")) } }))
            }
        }
    }

    companion object {
        @Volatile private var inst: Repo? = null
        fun get(ctx: Context): Repo = inst ?: synchronized(this) { inst ?: Repo(ctx.applicationContext).also { inst = it } }

        fun companyToJson(c: Company) = JSONObject().apply {
            put("id", c.id); put("name", c.name); put("start", c.startDate.toString()); put("end", c.endDate?.toString() ?: "")
            put("lat", c.lat); put("lon", c.lon); put("radius", c.radiusM); put("ws", c.workStart); put("we", c.workEnd)
            put("lunch", c.lunchMin); put("base", c.baseSalary); put("linked", c.baseLinkedToMinWage); put("otRate", c.overtimeRate)
            put("otUnit", c.overtimeUnitMin); put("bonus", c.fullAttendanceBonus); put("weekend", JSONArray(c.weekendDays.toList()))
            put("xh", JSONArray(c.extraHolidays.toList())); put("xw", JSONArray(c.extraWorkdays.toList()))
            put("pStart", c.periodStartDay); put("payDay", c.payDay); put("payOff", c.payMonthOffset); put("payAdj", c.payAdjust)
            put("prorate", c.prorateBy); put("bonusPartial", c.bonusOnPartial)
            put("leaveDays", c.annualLeaveDays); put("leaveBasis", c.leaveBasis); put("leaveCarry", c.leaveCarryover)
        }

        fun companyFromJson(j: JSONObject): Company {
            val d = Company()
            fun strs(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
            return Company(j.optLong("id", d.id), j.optString("name", d.name),
                runCatching { LocalDate.parse(j.getString("start")) }.getOrDefault(d.startDate),
                j.optString("end").takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                j.optDouble("lat", 0.0), j.optDouble("lon", 0.0), j.optDouble("radius", d.radiusM),
                j.optString("ws", d.workStart), j.optString("we", d.workEnd), j.optInt("lunch", d.lunchMin),
                j.optInt("base", d.baseSalary), j.optBoolean("linked", false), j.optDouble("otRate", d.overtimeRate),
                j.optInt("otUnit", d.overtimeUnitMin), j.optInt("bonus", d.fullAttendanceBonus),
                j.optJSONArray("weekend")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: d.weekendDays,
                strs("xh"), strs("xw"),
                j.optInt("pStart", d.periodStartDay), j.optInt("payDay", d.payDay), j.optInt("payOff", d.payMonthOffset),
                j.optString("payAdj", d.payAdjust), j.optString("prorate", d.prorateBy), j.optBoolean("bonusPartial", d.bonusOnPartial),
                j.optInt("leaveDays", d.annualLeaveDays), j.optString("leaveBasis", d.leaveBasis), j.optDouble("leaveCarry", d.leaveCarryover))
        }

        fun settingsToJson(s: AppSettings) = JSONObject().apply {
            put("mw", JSONObject().apply { s.minWageTable.forEach { (k, v) -> put(k.toString(), v) } })
            put("mwOver", s.minWageOverride); put("basis", s.hourlyBasis); put("hwRate", s.holidayWorkRate)
            put("leaveOk", s.leaveCountsAsAttendance); put("dedAbs", s.deductAbsence); put("dedEarly", s.deductEarlyLeave)
            put("exitMin", s.exitConfirmMin); put("exitN", s.exitConfirmSamples); put("fast", s.fastExitM); put("acc", s.maxAccuracyM)
            put("cutoff", s.dayCutoff); put("dep", s.dependents); put("child", s.children820); put("ratio", s.withholdingRatio)
            put("nontax", s.nontaxableMonthly); put("insBase", s.insuranceBaseOverride); put("pFloor", s.pensionFloor)
            put("pCap", s.pensionCap); put("itax", s.incomeTaxOverride); put("weekLimit", s.weeklyOvertimeLimitMin)
            put("ins", JSONObject().apply {
                s.insOverride.forEach { (y, r) -> put(y.toString(), JSONArray(listOf(r.pension, r.health, r.ltc, r.employment))) }
            })
        }

        fun settingsFromJson(j: JSONObject): AppSettings {
            val d = AppSettings()
            val mw = j.optJSONObject("mw")?.let { o -> o.keys().asSequence().associate { it.toInt() to o.getInt(it) } }
            val table = DEFAULT_MIN_WAGE + (mw ?: emptyMap()) // 앱 업데이트로 추가된 연도 자동 반영, 사용자 값 우선
            val ins = j.optJSONObject("ins")?.let { o ->
                o.keys().asSequence().associate { k -> val a = o.getJSONArray(k); k.toInt() to InsRates(a.getDouble(0), a.getDouble(1), a.getDouble(2), a.getDouble(3)) }
            } ?: emptyMap()
            return AppSettings(table, j.optInt("mwOver", 0), j.optString("basis", d.hourlyBasis), j.optDouble("hwRate", d.holidayWorkRate),
                j.optBoolean("leaveOk", true), j.optBoolean("dedAbs", true), j.optBoolean("dedEarly", false),
                j.optInt("exitMin", d.exitConfirmMin), j.optInt("exitN", d.exitConfirmSamples), j.optDouble("fast", d.fastExitM),
                j.optDouble("acc", d.maxAccuracyM), j.optString("cutoff", d.dayCutoff), j.optInt("dep", 1), j.optInt("child", 0),
                j.optInt("ratio", 100), j.optInt("nontax", 0), j.optInt("insBase", 0), j.optInt("pFloor", d.pensionFloor),
                j.optInt("pCap", d.pensionCap), ins, j.optInt("itax", -1), j.optInt("weekLimit", d.weeklyOvertimeLimitMin))
        }
    }
}
