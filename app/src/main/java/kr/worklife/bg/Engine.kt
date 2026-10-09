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
        val g = GeofenceTracker(c, repo.settings, d)
        g.feedAll(locs)
        return Result(null, g, c)
    }

    private fun toRecord(c: Company, d: LocalDate, g: GeofenceTracker, co: LocalDateTime, src: Source): DayRecord {
        val workday = HolidayCalendar(c).isWorkday(d)
        val notes = g.notes.toMutableList()
        val ci = if (!workday) g.firstInside?.toLocalTime()?.withSecond(0)?.withNano(0) ?: hm(c.workStart) else hm(c.workStart)
        g.firstInside?.let { if (workday && it.toLocalTime().isAfter(hm(c.workStart).plusMinutes(10))) notes.add(0, "첫 사업장 확인 ${it.toLocalTime().hhmm()}") }
        return DayRecord(d, c.id, if (workday) Status.WORK else Status.HOLIDAY_WORK, ci,
            co.toLocalTime().withSecond(0).withNano(0), src, notes.joinToString(" / "))
    }

    /** 오늘: 퇴근이 확정됐으면 저장하고 기록 반환 (수동 기록은 절대 덮어쓰지 않음) */
    fun checkToday(ctx: Context, now: LocalDateTime = LocalDateTime.now()): DayRecord? {
        val d = now.toLocalDate()
        val repo = Repo.get(ctx)
        val (_, g, c) = evaluate(ctx, d)
        if (g == null || c == null) return null
        if (!HolidayCalendar(c).isWorkday(d) && g.firstInside == null) return null
        val existing = repo.day(d, c.id)
        if (existing != null && existing.source == Source.MANUAL) return existing
        val co = g.checkout ?: run {
            // 마감 시각 지남 → 강제 마감
            if (now.toLocalTime().isAfter(hm(repo.settings.dayCutoff))) g.finalize().first else return null
        }
        val src = if (g.checkout != null) Source.AUTO else Source.NEEDS_CHECK
        val rec = toRecord(c, d, g, co, src)
        if (existing != rec) repo.saveDay(rec)
        return rec
    }

    /** 지난 날 중 확정 기록 없이 위치만 있는 날 자동 마감 */
    fun settlePast(ctx: Context, today: LocalDate = LocalDate.now()) {
        val repo = Repo.get(ctx)
        for (back in 1L..40L) {
            val d = today.minusDays(back)
            val c = repo.companyOn(d) ?: continue
            if (repo.day(d, c.id) != null) continue
            val (_, g, _) = evaluate(ctx, d)
            if (g == null) continue
            if (!HolidayCalendar(c).isWorkday(d) && g.firstInside == null) continue
            val (co, src) = g.finalize()
            repo.saveDay(toRecord(c, d, g, co, src))
        }
        repo.pruneLocs()
    }
}
