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

class Repo private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "worklife.db", null, 4) {
    private val prefs = ctx.getSharedPreferences("worklife", Context.MODE_PRIVATE)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE days(day TEXT, company INTEGER, status TEXT, ci TEXT, co TEXT, src TEXT, note TEXT, lov INTEGER DEFAULT 0, PRIMARY KEY(day, company))")
        db.execSQL("CREATE TABLE locs(ts TEXT, lat REAL, lon REAL, acc REAL, kind TEXT)")
        db.execSQL("CREATE INDEX idx_locs ON locs(ts)")
        db.execSQL("CREATE TABLE payslips(ym TEXT, company INTEGER, json TEXT, kind TEXT, saved_at TEXT, PRIMARY KEY(ym, company))")
        createCards(db)
    }

    private fun createCards(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cards(id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT, amount INTEGER, merchant TEXT, card TEXT, sender TEXT, name TEXT, inst INTEGER, foreign_amt TEXT, bc INTEGER, source TEXT, raw TEXT, excluded INTEGER DEFAULT 0, person INTEGER DEFAULT -1)")
        db.execSQL("CREATE INDEX idx_cards ON cards(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        // v2: 위치 기록에 종류(fix/enter/exit) 추가 — 기존 기록은 그대로 유지
        if (o < 2) runCatching { db.execSQL("ALTER TABLE locs ADD COLUMN kind TEXT") }
        // v3: 점심 초과 시간(분)
        if (o < 3) runCatching { db.execSQL("ALTER TABLE days ADD COLUMN lov INTEGER DEFAULT 0") }
        // v4: 카드 사용 내역
        if (o < 4) runCatching { createCards(db) }
    }

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
    /** 재직 중인 회사 (오늘 기준). 없으면 null = 퇴사 상태 */
    fun activeCompany(): Company? = companyOn(LocalDate.now())

    /**
     * 회사 옮기기: 지금 회사는 prevEnd(마지막 근무일)로 퇴사 처리, 새 회사는 newStart 입사
     * (퇴사일과 입사일 사이 공백 허용). 근무조건은 복사, 위치·휴무일·퇴직금 보조값은 비움
     */
    fun moveCompany(newName: String, prevEnd: LocalDate?, newStart: LocalDate): Company {
        val base = (if (prevEnd != null) companyOn(prevEnd) else null) ?: companies.maxByOrNull { it.startDate } ?: Company()
        val list = companies.map { if (prevEnd != null && it.id == base.id) it.copy(endDate = prevEnd) else it }
        val nc = base.copy(id = (companies.maxOfOrNull { it.id } ?: 0) + 1, name = newName, startDate = newStart, endDate = null,
            lat = 0.0, lon = 0.0, extraHolidays = emptySet(), extraWorkdays = emptySet(),
            startConfirmed = true, annualBonus = 0, unusedLeavePay = 0)
        companies = list + nc
        return nc
    }

    /** 퇴사: 마지막 근무일 지정 */
    fun retire(cid: Long, lastDay: LocalDate) { companies = companies.map { if (it.id == cid) it.copy(endDate = lastDay) else it } }

    /** 퇴사 취소 */
    fun unretire(cid: Long) { companies = companies.map { if (it.id == cid) it.copy(endDate = null) else it } }

    /** 퇴직 전 3개월 임금총액 (확정·직접입력 급여 > 기록으로 계산 > 기본급 추정). 두 번째 값 = 추정으로 채운 달 수 */
    fun wagesBefore(c: Company, lastDay: LocalDate): Pair<Long, Int> {
        val (from, to) = threeMonthWindow(lastDay)
        val s = settings
        val snaps = savedPays().filter { it.companyId == c.id }.associateBy { it.ym }
        var total = 0.0; var estimated = 0
        var ym = YearMonth.from(from)
        while (!ym.isAfter(YearMonth.from(to))) {
            val mStart = maxOf(ym.atDay(1), from); val mEnd = minOf(ym.atEndOfMonth(), to)
            val share = (java.time.temporal.ChronoUnit.DAYS.between(mStart, mEnd) + 1).toDouble() / ym.lengthOfMonth()
            val snap = snaps[ym]
            val gross = if (snap != null) snap.gross.toDouble() else {
                val p = periodOf(c, ym)
                val stored = period(p, c.id)
                if (stored.isEmpty()) estimated++
                calcPayroll(c, s, p, buildPeriod(c, p, stored, LocalDate.now()), LocalDate.now()).gross.toDouble()
            }
            total += gross * share
            ym = ym.plusMonths(1)
        }
        return total.toLong() to estimated
    }

    // ───────── 재미 탭 (점심 메뉴·돌림판) ─────────
    var lunchMenus: List<String>
        get() = strList("lunchMenus") ?: DEFAULT_MENUS
        set(v) = putList("lunchMenus", v)
    /** 최근 점심 결과 "날짜|메뉴" */
    var lunchHistory: List<String>
        get() = strList("lunchHist") ?: emptyList()
        set(v) = putList("lunchHist", v.takeLast(30))
    fun recentLunch(days: Long): Set<String> = lunchHistory.mapNotNull { e ->
        val (d, m) = e.split("|", limit = 2).let { if (it.size == 2) it[0] to it[1] else return@mapNotNull null }
        runCatching { LocalDate.parse(d) }.getOrNull()?.takeIf { !it.isBefore(LocalDate.now().minusDays(days)) }?.let { m }
    }.toSet()
    /** 저장한 돌림판: 이름 → 항목 */
    var wheels: Map<String, List<String>>
        get() = runCatching {
            val o = JSONObject(prefs.getString("wheels", "{}")!!)
            o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } } }
        }.getOrDefault(emptyMap()).ifEmpty { mapOf("커피 쏠 사람" to listOf("나", "김대리", "박과장", "이주임")) }
        set(v) { prefs.edit().putString("wheels", JSONObject().apply { v.forEach { (k, l) -> put(k, JSONArray(l)) } }.toString()).apply() }

    private fun strList(k: String): List<String>? = prefs.getString(k, null)?.let { s ->
        runCatching { JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrNull()
    }
    private fun putList(k: String, v: List<String>) { prefs.edit().putString(k, JSONArray(v).toString()).apply() }

    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(v) { prefs.edit().putBoolean("onboarded", v).apply() }

    // ───────── 근태 ─────────
    fun saveDay(r: DayRecord) {
        logDay(r.day, "저장 ${r.status.label} ${r.checkIn?.hhmm() ?: "-"}~${r.checkOut?.hhmm() ?: "-"} [${r.source.label}]" +
            (if (r.note.isNotBlank()) " ${r.note.take(40)}" else "") + " ← ${caller()}")
        writableDatabase.insertWithOnConflict("days", null, ContentValues().apply {
            put("day", r.day.toString()); put("company", r.companyId); put("status", r.status.name)
            put("ci", r.checkIn?.hhmm()); put("co", r.checkOut?.hhmm()); put("src", r.source.name); put("note", r.note); put("lov", r.lunchOverMin)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteDay(d: LocalDate, companyId: Long) {
        logDay(d, "삭제 ← ${caller()}")
        writableDatabase.delete("days", "day=? AND company=?", arrayOf(d.toString(), companyId.toString()))
    }

    fun day(d: LocalDate, companyId: Long): DayRecord? = queryDays("day=? AND company=?", arrayOf(d.toString(), companyId.toString())).firstOrNull()

    fun month(ym: YearMonth, companyId: Long): Map<LocalDate, DayRecord> =
        queryDays("day LIKE ? AND company=?", arrayOf("$ym-%", companyId.toString())).associateBy { it.day }

    /** 기간 조회 (급여 산정기간이 달을 걸쳐도 OK) */
    fun range(start: LocalDate, end: LocalDate, companyId: Long): Map<LocalDate, DayRecord> =
        queryDays("day >= ? AND day <= ? AND company=?", arrayOf(start.toString(), end.toString(), companyId.toString())).associateBy { it.day }

    fun period(p: PayPeriod, companyId: Long) = range(p.start, p.end, companyId)

    /** 퇴근 시간대 학습용: 최근 n일 자동 퇴근 시각 (근무일·자동 기록만) */
    fun recentAutoCheckouts(companyId: Long, days: Long = 60): List<LocalTime> =
        range(LocalDate.now().minusDays(days), LocalDate.now(), companyId).values
            .filter { it.status == Status.WORK && it.source == Source.AUTO && it.checkOut != null }.map { it.checkOut!! }

    fun hasLocs(d: LocalDate): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM locs WHERE ts LIKE ? LIMIT 1", arrayOf("$d%")).use { it.moveToFirst() }

    /**
     * 근무시간·휴일 설정이 바뀌면 자동으로 만든 기록을 새 기준으로 다시 맞춤 (직접 입력한 기록은 그대로)
     *  - 위치 기록이 남아있는 날: 지우고 새 기준으로 재판정 (Engine.settlePast)
     *  - 위치 기록이 없는 날: 출근시각은 새 값, '기본' 기록은 퇴근도 새 값, 자동 퇴근 시각은 실제 시각이라 유지
     *  - 새 기준에서 휴일이 된 날: 자동 기록 삭제
     */
    fun reapplyCompany(c: Company): Int {
        val cal = HolidayCalendar(c)
        var n = 0
        val all = queryDays("company=?", arrayOf(c.id.toString()))
        for (r in all) {
            if (r.source == Source.MANUAL) continue
            if (r.status != Status.WORK && r.status != Status.HOLIDAY_WORK) continue
            n++
            if (!cal.isWorkday(r.day) || hasLocs(r.day)) { deleteDay(r.day, c.id); continue }
            saveDay(r.copy(checkIn = hm(c.workStart), checkOut = if (r.source == Source.DEFAULT) hm(c.workEnd) else r.checkOut))
        }
        return n
    }

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
                        runCatching { Source.valueOf(c.getString(5)) }.getOrDefault(Source.MANUAL), c.getString(6) ?: "",
                        if (c.columnCount > 7 && !c.isNull(7)) c.getInt(7) else 0))
                }
            }
        }

    // ───────── 위치 ─────────
    fun addLoc(s: LocSample) {
        writableDatabase.insert("locs", null, ContentValues().apply {
            put("ts", s.ts.withNano(0).toString()); put("lat", s.lat); put("lon", s.lon); put("acc", s.accuracy); put("kind", s.kind)
        })
    }

    fun locsOf(d: LocalDate): List<LocSample> =
        readableDatabase.query("locs", null, "ts LIKE ?", arrayOf("$d%"), null, null, "ts").use { c ->
            buildList { while (c.moveToNext()) add(LocSample(LocalDateTime.parse(c.getString(0)), c.getDouble(1), c.getDouble(2), c.getDouble(3),
                if (c.columnCount > 4 && !c.isNull(4)) c.getString(4) else "fix")) }
        }

    /** 위치 기록은 60일만 보관 (근태 기록은 영구) */
    fun pruneLocs() {
        writableDatabase.delete("locs", "ts < ?", arrayOf(LocalDate.now().minusDays(60).toString()))
        val old = LocalDate.now().minusDays(60).toString()
        val e = prefs.edit()
        prefs.all.keys.filter { (it.startsWith("dlog-") || it.startsWith("resume-")) && it.substringAfter("-") < old }.forEach { e.remove(it) }
        e.apply()
    }

    // ───────── 기록 이력 (누가·언제 저장/삭제했는지: 퇴근이 엉뚱하게 잡혔을 때 원인 확인용) ─────────
    private fun caller(): String {
        val names = mapOf("checkoutNow" to "위젯 [지금 퇴근]", "checkToday" to "자동 판정", "settlePast" to "지난 날 마감",
            "reevaluateOnUpgrade" to "업데이트 후 재판정", "reapplyCompany" to "근무시간 변경 반영", "setFieldWork" to "외근",
            "resumeWork" to "퇴근 취소", "onReceive" to "알림 버튼")
        val st = Throwable().stackTrace
        for (f in st.drop(2)) {
            val m = f.methodName.substringBefore('$').substringBefore('-')
            names[m]?.let { return it }
            if (f.className.contains("HomeScreen")) return "홈 화면 버튼"
            if (f.className.contains("RecordsScreen")) return "근무기록 수정"
            if (f.className.contains("SettingsScreen")) return "설정"
        }
        return st.getOrNull(2)?.let { "${it.className.substringAfterLast('.')}.${it.methodName}" } ?: "?"
    }

    fun logDay(d: LocalDate, msg: String) {
        val key = "dlog-$d"
        val lines = (prefs.getString(key, "") ?: "").lines().filter { it.isNotBlank() }.takeLast(14)
        prefs.edit().putString(key, (lines + "${java.time.LocalTime.now().withNano(0)} $msg").joinToString("\n")).apply()
    }

    fun dayLog(d: LocalDate): List<String> = (prefs.getString("dlog-$d", "") ?: "").lines().filter { it.isNotBlank() }

    /** [퇴근 취소] 시각 (그 시각부터 다시 판정) */
    fun resumeAt(d: LocalDate): java.time.LocalDateTime? = prefs.getString("resume-$d", null)?.let { runCatching { java.time.LocalDateTime.parse(it) }.getOrNull() }
    fun setResume(d: LocalDate, t: java.time.LocalDateTime?) { prefs.edit().apply { if (t == null) remove("resume-$d") else putString("resume-$d", t.withNano(0).toString()) }.apply() }

    /** 퇴근 음성 안내 켜기/끄기 */
    var voiceOn: Boolean
        get() = prefs.getBoolean("voiceOn", false)
        set(v) { prefs.edit().putBoolean("voiceOn", v).apply() }

    // ───────── 카드 사용 내역 ─────────
    private fun txValues(t: CardTx) = ContentValues().apply {
        put("ts", t.ts.withSecond(0).withNano(0).toString()); put("amount", t.amount); put("merchant", t.merchant); put("card", t.card)
        put("sender", t.sender); put("name", t.name); put("inst", t.installment); put("foreign_amt", t.foreign); put("bc", if (t.isBc) 1 else 0)
        put("source", t.source); put("raw", t.raw); put("excluded", if (t.excluded) 1 else 0); put("person", t.person)
    }

    /** 새 내역 저장. 이미 있는 결제(문자+알림톡 중복 등)면 저장 안 하고 false */
    @Synchronized
    fun addCardTx(t: CardTx): Boolean {
        val near = cardTxs("ts >= ? AND ts <= ?", arrayOf(t.ts.minusMinutes(4).toString(), t.ts.plusMinutes(4).toString()))
        if (near.any { sameCardTx(it, t) || (it.raw.isNotEmpty() && it.raw == t.raw && it.ts == t.ts.withSecond(0).withNano(0)) }) return false
        writableDatabase.insert("cards", null, txValues(t))
        return true
    }

    fun updateCardTx(t: CardTx) { writableDatabase.update("cards", txValues(t), "id=?", arrayOf(t.id.toString())) }
    fun deleteCardTx(id: Long) { writableDatabase.delete("cards", "id=?", arrayOf(id.toString())) }

    fun cardTxs(from: LocalDate, to: LocalDate): List<CardTx> =
        cardTxs("ts >= ? AND ts < ?", arrayOf(from.atStartOfDay().toString(), to.plusDays(1).atStartOfDay().toString()))

    fun cardTxCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM cards", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun cardTxs(where: String, args: Array<String>): List<CardTx> =
        readableDatabase.query("cards", null, where, args, null, null, "ts DESC").use { c ->
            fun str(n: String) = c.getColumnIndex(n).let { if (it < 0 || c.isNull(it)) "" else c.getString(it) }
            fun int(n: String) = c.getColumnIndex(n).let { if (it < 0 || c.isNull(it)) 0L else c.getLong(it) }
            buildList {
                while (c.moveToNext()) runCatching {
                    add(CardTx(int("id"), LocalDateTime.parse(str("ts")), int("amount"), str("merchant"), str("card"), str("sender"), str("name"),
                        int("inst").toInt(), str("foreign_amt"), int("bc") == 1L, str("source").ifEmpty { "sms" }, str("raw"),
                        int("excluded") == 1L, c.getColumnIndex("person").let { if (it < 0 || c.isNull(it)) -1 else c.getInt(it) }))
                }
            }
        }

    var cardPeople: List<CardPerson>
        get() = runCatching {
            val a = JSONArray(prefs.getString("cardPeople", "[]"))
            (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
                CardPerson(o.optString("name"), o.optString("phone"),
                    o.optString("cards").split(",").map { it.trim() }.filter { it.isNotEmpty() }, o.optString("hint"))
            } }
        }.getOrDefault(emptyList())
        set(v) { prefs.edit().putString("cardPeople", JSONArray(v.take(CardPerson.MAX).map { p ->
            JSONObject().put("name", p.name).put("phone", p.phone).put("cards", p.cards.joinToString(",")).put("hint", p.nameHint) }).toString()).apply() }

    var cardPeriod: CardPeriod
        get() = runCatching { JSONObject(prefs.getString("cardPeriod", "{}")!!).let { o ->
            CardPeriod(o.optString("mode", "month"), o.optInt("cycle", 1),
                o.optString("s").takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) },
                o.optString("e").takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }) } }.getOrDefault(CardPeriod())
        set(v) { prefs.edit().putString("cardPeriod", JSONObject().put("mode", v.mode).put("cycle", v.cycleStartDay)
            .put("s", v.customStart?.toString() ?: "").put("e", v.customEnd?.toString() ?: "").toString()).apply() }

    /** BC 문자만 합산 (등록한 카드·번호는 항상 포함) */
    var cardBcOnly: Boolean
        get() = prefs.getBoolean("cardBcOnly", true)
        set(v) { prefs.edit().putBoolean("cardBcOnly", v).apply() }

    var cardKeywords: List<String>
        get() = (prefs.getString("cardKeywords", null) ?: CardParser.DEFAULT_KEYWORDS.joinToString(",")).split(",").map { it.trim() }.filter { it.isNotEmpty() }
        set(v) { prefs.edit().putString("cardKeywords", v.joinToString(",")).apply() }

    /** 문자함에서 마지막으로 읽은 문자 시각(ms) — 다음엔 그 뒤만 읽음 */
    var cardSmsScannedUntil: Long
        get() = prefs.getLong("cardSmsScan", 0L)
        set(v) { prefs.edit().putLong("cardSmsScan", v).apply() }

    /** 큰 위젯 아랫줄: card / net / holiday / week / none */
    var widgetExtra: String
        get() = prefs.getString("widgetExtra", "card") ?: "card"
        set(v) { prefs.edit().putString("widgetExtra", v).apply() }

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
        val DEFAULT_MENUS = listOf("김치찌개", "된장찌개", "제육볶음", "순두부찌개", "부대찌개", "비빔밥", "국밥", "칼국수", "냉면",
            "불고기", "닭갈비", "짜장면", "짬뽕", "탕수육", "볶음밥", "돈까스", "초밥", "라멘", "우동", "덮밥",
            "김밥", "떡볶이", "라면", "햄버거", "샌드위치", "파스타", "피자", "쌀국수", "샐러드", "도시락")
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
            put("lunchS", c.lunchStart); put("lunchE", c.lunchEnd); put("lunchDed", c.lunchOverDeduct)
            put("startOk", c.startConfirmed); put("bonusY", c.annualBonus); put("leavePay", c.unusedLeavePay)
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
                j.optInt("leaveDays", d.annualLeaveDays), j.optString("leaveBasis", d.leaveBasis), j.optDouble("leaveCarry", d.leaveCarryover),
                j.optString("lunchS", d.lunchStart), j.optString("lunchE", d.lunchEnd), j.optBoolean("lunchDed", d.lunchOverDeduct),
                j.optBoolean("startOk", false), j.optInt("bonusY", 0), j.optInt("leavePay", 0))
        }

        fun settingsToJson(s: AppSettings) = JSONObject().apply {
            put("mw", JSONObject().apply { s.minWageTable.forEach { (k, v) -> put(k.toString(), v) } })
            put("mwOver", s.minWageOverride); put("basis", s.hourlyBasis); put("hwRate", s.holidayWorkRate)
            put("leaveOk", s.leaveCountsAsAttendance); put("dedAbs", s.deductAbsence); put("dedEarly", s.deductEarlyLeave)
            put("exitMin", s.exitConfirmMin); put("exitN", s.exitConfirmSamples); put("fast", s.fastExitM); put("acc", s.maxAccuracyM)
            put("cutoff", s.dayCutoff); put("dep", s.dependents); put("child", s.children820); put("ratio", s.withholdingRatio)
            put("nontax", s.nontaxableMonthly); put("insBase", s.insuranceBaseOverride); put("pFloor", s.pensionFloor)
            put("pCap", s.pensionCap); put("itax", s.incomeTaxOverride); put("weekLimit", s.weeklyOvertimeLimitMin)
            put("pollEnd", s.pollAfterEndMin); put("fastOn", s.fastLearnedWindow); put("fastMin", s.fastIntervalMin); put("holPoll", s.holidayPollMin)
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
                j.optInt("pCap", d.pensionCap), ins, j.optInt("itax", -1), j.optInt("weekLimit", d.weeklyOvertimeLimitMin),
                j.optInt("pollEnd", d.pollAfterEndMin), j.optBoolean("fastOn", d.fastLearnedWindow),
                j.optInt("fastMin", d.fastIntervalMin), j.optInt("holPoll", d.holidayPollMin))
        }
    }
}
