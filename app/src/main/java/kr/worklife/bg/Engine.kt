package kr.worklife.bg

import android.content.Context
import kr.worklife.core.*
import kr.worklife.data.Repo
import java.time.LocalDate
import java.time.LocalDateTime

/** 위치 기록 → 근태 기록 변환. 서비스·알람·화면이 모두 이 한 곳을 거친다 */
object Engine {
    data class Result(val record: DayRecord?, val tracker: GeofenceTracker?, val company: Company?)

    fun evaluate(ctx: Context, d: LocalDate): Result {
        val repo = Repo.get(ctx)
        val c = repo.companyOn(d) ?: return Result(null, null, null)
        if (!c.hasLocation) return Result(null, null, c)
        val locs = repo.locsOf(d)
        if (locs.isEmpty()) return Result(null, null, c)
        val g = GeofenceTracker(c, repo.settings, d, holiday = !HolidayCalendar(c).isWorkday(d))
        g.feedAll(locs, repo.resumeAt(d))
        return Result(null, g, c)
    }

    private fun toRecord(c: Company, d: LocalDate, g: GeofenceTracker, co: LocalDateTime, src: Source): DayRecord {
        val workday = HolidayCalendar(c).isWorkday(d)
        val notes = g.notes.toMutableList()
        val ci = if (!workday) g.firstInside?.toLocalTime()?.withSecond(0)?.withNano(0) ?: hm(c.workStart) else hm(c.workStart)
        g.firstInside?.let { if (workday && it.toLocalTime().isAfter(hm(c.workStart).plusMinutes(10))) notes.add(0, "첫 사업장 확인 ${it.toLocalTime().hhmm()}") }
        return DayRecord(d, c.id, if (workday) Status.WORK else Status.HOLIDAY_WORK, ci,
            co.toLocalTime().withSecond(0).withNano(0), src, notes.joinToString(" / "), g.lunchOverMin)
    }

    /** 오늘: 퇴근이 확정됐으면 저장하고 기록 반환 (수동 기록은 절대 덮어쓰지 않음) */
    fun checkToday(ctx: Context, now: LocalDateTime = LocalDateTime.now()): DayRecord? {
        val d = now.toLocalDate()
        val repo = Repo.get(ctx)
        val (_, g0, c) = evaluate(ctx, d)
        if (c == null) return null
        // [퇴근 취소] 뒤 아직 위치가 안 들어왔어도 '근무 중' 유지
        val g = g0 ?: return null
        // 휴일엔 자동 기록하지 않음 (회사 반경 안이어도) → 홈에서 '휴일근무로 기록' 제안만
        if (!HolidayCalendar(c).isWorkday(d)) return null
        val existing = repo.day(d, c.id)
        if (existing != null && existing.source == Source.MANUAL) return existing
        val co = g.checkout ?: run {
            // 마감 시각 지남 → 강제 마감
            if (now.toLocalTime().isAfter(hm(repo.settings.dayCutoff))) g.finalize().first else return null
        }
        val src = if (g.checkout != null && !g.uncertain) Source.AUTO else Source.NEEDS_CHECK
        val rec = toRecord(c, d, g, co, src)
        if (existing != rec) repo.saveDay(rec)
        return rec
    }

    /** 휴일: 회사에 있다가 나간 게 확정되면 1회 알림 (자동 기록 대신 '휴일근무로 기록' 제안) */
    fun checkHoliday(ctx: Context, now: LocalDateTime = LocalDateTime.now()): GeofenceTracker? {
        val d = now.toLocalDate()
        val (_, g, c) = evaluate(ctx, d)
        if (g == null || c == null || HolidayCalendar(c).isWorkday(d)) return null
        val fi = g.firstInside ?: return g
        val co = g.checkout ?: return g
        val repo = Repo.get(ctx)
        val key = "holidayNotified-$d"
        val p = ctx.getSharedPreferences("worklife", Context.MODE_PRIVATE)
        if (repo.day(d, c.id) == null && !p.getBoolean(key, false) && java.time.Duration.between(fi, co).toMinutes() >= 30) {
            p.edit().putBoolean(key, true).apply()
            Notif.post(ctx, 14, "휴일에 ${c.name}에서 나오셨어요",
                "${fi.toLocalTime().hhmm()} ~ ${co.toLocalTime().hhmm()} · 근무하셨다면 앱에서 [휴일근무로 기록]을 눌러주세요.")
        }
        return g
    }

    /**
     * 홈 [퇴근 취소 · 아직 근무 중]: 오늘 퇴근 기록을 지우고 지금부터 다시 감지
     * (지금 회사에 있는 것으로 보고, 다시 반경을 벗어나면 그때 퇴근으로 기록)
     */
    fun resumeWork(ctx: Context, now: LocalDateTime = LocalDateTime.now()) {
        val repo = Repo.get(ctx)
        val d = now.toLocalDate()
        val c = repo.companyOn(d) ?: return
        repo.setResume(d, now)
        repo.deleteDay(d, c.id)
        ctx.getSharedPreferences("worklife", Context.MODE_PRIVATE).edit().remove("checkoutNotified-$d").apply()
        runCatching { Scheduler.ensure(ctx) }
        runCatching { Geo.register(ctx) }
        runCatching { Widgets.updateAll(ctx) }
    }

    const val FIELD_NOTE = "외근"

    /** 오늘 외근: 위치로 판정하지 않고 정시 퇴근으로 기록 (늦게 끝나면 퇴근시각 입력으로 수정) */
    fun setFieldWork(ctx: Context, on: Boolean, d: LocalDate = LocalDate.now()) {
        val repo = Repo.get(ctx)
        val c = repo.companyOn(d) ?: return
        if (on) repo.saveDay(DayRecord(d, c.id, Status.WORK, hm(c.workStart), hm(c.workEnd), Source.MANUAL, FIELD_NOTE))
        else { repo.deleteDay(d, c.id); checkToday(ctx) }
    }

    fun isFieldWork(r: DayRecord?) = r != null && r.source == Source.MANUAL && r.note == FIELD_NOTE

    /**
     * 점심 끝나고 30분 뒤 1회: 점심시간에 나가서 아직 회사 밖이면 [조퇴로 기록]/[외근·업무 중] 확인 알림
     * (추가 위치 확인 없이 회사 출입 알림 기록만으로 판단)
     */
    fun lunchReturnCheck(ctx: Context, now: LocalDateTime = LocalDateTime.now()) {
        val d = now.toLocalDate()
        val repo = Repo.get(ctx)
        val (_, g, c) = evaluate(ctx, d)
        if (g == null || c == null || !HolidayCalendar(c).isWorkday(d)) return
        if (repo.day(d, c.id) != null) return
        val os = g.outsideSince ?: return
        if (!c.inLunch(os.toLocalTime())) return
        val p = ctx.getSharedPreferences("worklife", Context.MODE_PRIVATE)
        val key = "lunchAsked-$d"
        if (p.getBoolean(key, false)) return
        p.edit().putBoolean(key, true).apply()
        Notif.lunchNotBack(ctx, os.toLocalTime())
    }

    /** 오늘이 급여일이면 아침 알림 (자정 마감 알람에서 호출) */
    fun paydayNotice(ctx: Context, today: LocalDate = LocalDate.now()) {
        val repo = Repo.get(ctx)
        for (c in repo.companies) {
            val np = nextPayday(c, today)
            if (np.payDate != today) continue
            val p = calcPayroll(c, repo.settings, np, buildPeriod(c, np, repo.period(np, c.id), today), today)
            Notif.post(ctx, 30 + c.id.toInt(), "오늘은 ${c.name} 급여일이에요",
                "${np.label.monthValue}월분(${np.rangeText()}) 예상 실수령 ${"%,d".format(p.net)}원")
        }
    }

    /**
     * 판정 방식이 바뀐 새 버전을 처음 실행하면, 최근 60일 자동 기록을 위치 기록으로 다시 계산 (직접 입력한 기록은 그대로)
     * → 이전 버전이 잘못 기록한 날(예: 16:50 퇴근 → 10:50)이 자동으로 바로잡힘
     */
    fun reevaluateOnUpgrade(ctx: Context, appVersion: Long) {
        val p = ctx.getSharedPreferences("worklife", Context.MODE_PRIVATE)
        if (p.getLong("reevalVersion", 0) >= appVersion) return
        val repo = Repo.get(ctx)
        val today = LocalDate.now()
        for (back in 0L..60L) {
            val d = today.minusDays(back)
            val c = repo.companyOn(d) ?: continue
            val r = repo.day(d, c.id) ?: continue
            if (r.source == Source.MANUAL || r.status != Status.WORK) continue
            if (!repo.hasLocs(d)) continue
            repo.deleteDay(d, c.id)
        }
        settlePast(ctx, today)
        checkToday(ctx)
        p.edit().putLong("reevalVersion", appVersion).apply()
    }

    /** 지난 날 중 확정 기록 없이 위치만 있는 날 자동 마감 */
    fun settlePast(ctx: Context, today: LocalDate = LocalDate.now()) {
        val repo = Repo.get(ctx)
        for (back in 1L..60L) {
            val d = today.minusDays(back)
            val c = repo.companyOn(d) ?: continue
            if (repo.day(d, c.id) != null) continue
            val (_, g, _) = evaluate(ctx, d)
            if (g == null) continue
            if (!HolidayCalendar(c).isWorkday(d)) continue
            val (co, src) = g.finalize()
            repo.saveDay(toRecord(c, d, g, co, src))
        }
        repo.pruneLocs()
    }
}
