package kr.worklife.bg

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kr.worklife.R
import kr.worklife.core.*
import kr.worklife.data.Repo
import kr.worklife.ui.MainActivity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** 위젯에 보여줄 값 (앱 홈과 같은 계산을 사용) */
data class WidgetData(
    val company: String, val status: String, val sub: String, val working: Boolean,
    val otToday: String, val otWeek: String, val otMonth: String,
    val pay: String, val leave: String, val canCheckout: Boolean,
    val extra: String = "",
)

object Widgets {
    const val ACTION_CHECKOUT = "kr.worklife.WIDGET_CHECKOUT"

    private fun hmS(m: Int) = if (m == 0) "0분" else if (m % 60 == 0) "${m / 60}시간" else if (m < 60) "${m}분" else "${m / 60}시간 ${m % 60}분"
    private fun won(v: Int) = "%,d원".format(v)
    private fun days(v: Double) = if (v % 1.0 == 0.0) "${v.toInt()}일" else "${"%.1f".format(v)}일"

    fun compute(ctx: Context, now: LocalDateTime = LocalDateTime.now()): WidgetData {
        val repo = Repo.get(ctx)
        val today = now.toLocalDate()
        val c = repo.activeCompany()
            ?: return WidgetData("슬기로운 직장생활", "재직 중인 회사 없음", "앱 > 설정에서 새 회사를 등록하세요", false,
                "-", "-", "-", "", "", false)
        val s = repo.settings
        val cal = HolidayCalendar(c)
        val hol = cal.holidayName(today)
        val period = periodContaining(c, today)
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val stored = repo.range(if (monday.isBefore(period.start)) monday else period.start, period.end, c.id)
        val rec = stored[today]
        val tracker = Engine.evaluate(ctx, today).tracker
        val live = if (hol == null && rec?.checkOut == null && (rec == null || rec.status == Status.WORK)) tracker?.liveOvertimeMin(now) ?: 0 else 0
        val sum = overtimeSummary(c, s, today, stored, live)
        val t = now.toLocalTime()
        val start = hm(c.workStart); val end = hm(c.workEnd)

        var working = false
        val (status, sub) = when {
            rec?.status == Status.ABSENT -> "결근" to ""
            rec?.status == Status.LEAVE -> "연차" to "오늘은 쉬어요"
            rec?.status == Status.HALF_LEAVE -> "반차" to ""
            Engine.isFieldWork(rec) -> { working = t.isAfter(start) && t.isBefore(end); "외근" to "정시 ${c.workEnd} 퇴근으로 기록" }
            rec?.checkOut != null -> {
                val ot = overtimeMin(c, rec)
                "퇴근 ${rec.checkOut.hhmm()}" to (if (ot > 0) "연장 ${hmS(ot)}" else "정시 퇴근 · 수고하셨어요")
            }
            hol != null -> "쉬는 날" to hol
            t.isBefore(start) -> "출근 전" to "${c.workStart} 출근"
            t.isBefore(end) -> { working = true; "근무 중" to "정시까지 ${hmS(ChronoUnit.MINUTES.between(t, end).toInt())}" }
            live > 0 -> { working = true; "연장 근무 중" to "+${hmS(live)}" }
            else -> { working = true; "퇴근 감지 중" to "회사를 벗어나면 자동 기록" }
        }
        val np = nextPayday(c, today)
        val npPay = calcPayroll(c, s, np, buildPeriod(c, np, repo.period(np, c.id), today), today)
        val dday = ChronoUnit.DAYS.between(today, np.payDate)
        val (ls, le) = leaveWindow(c, today)
        val lv = leaveSummary(c, today, repo.range(ls, le, c.id).values.toList())
        val canOut = hol == null && rec?.checkOut == null && (rec == null || rec.status == Status.WORK) && t.isAfter(start)
        return WidgetData(c.name, status, sub, working,
            hmS(sum.todayMin), hmS(sum.weekMin), hmS(sum.monthMin),
            "급여일 ${np.payDate.monthValue}/${np.payDate.dayOfMonth} " + (if (dday == 0L) "오늘!" else "D-$dday") + " · 예상 ${won(npPay.net)}",
            "남은 연차 ${days(lv.remaining)}", canOut, extraLine(ctx, c, today, sum))
    }

    /** 큰 위젯 아랫줄 (설정에서 선택) */
    fun extraLine(ctx: Context, c: Company, today: LocalDate, sum: OtSummary? = null): String = runCatching {
        val repo = Repo.get(ctx)
        when (repo.widgetExtra) {
            "card" -> {
                val p = repo.cardPeriod
                val (s, e) = cardRange(p, today)
                val sm = summarizeCards(repo.cardTxs(s, e), repo.cardPeople, s, e, repo.cardBcOnly)
                "💳 카드 ${s.monthValue}/${s.dayOfMonth}~ 사용 ${won(sm.total.toInt())} (${sm.count}건)"
            }
            "holiday" -> {
                val cal = HolidayCalendar(c)
                val wk = setOf("토요일", "일요일", "휴무요일")
                val next = (1L..400L).map { today.plusDays(it) }.firstOrNull { d -> cal.holidayName(d)?.let { it !in wk } == true }
                if (next == null) "" else "🎉 ${cal.holidayName(next)} ${next.monthValue}/${next.dayOfMonth} · D-${ChronoUnit.DAYS.between(today, next)}"
            }
            "week" -> sum?.let { "⏱ 이번 주 연장 ${hmS(it.weekMin)} / ${it.weekLimitMin / 60}시간" } ?: ""
            else -> ""
        }
    }.getOrDefault("")

    private fun openApp(ctx: Context) = PendingIntent.getActivity(ctx, 100,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun small(ctx: Context, d: WidgetData) = RemoteViews(ctx.packageName, R.layout.widget_small).apply {
        setInt(R.id.w_root, "setBackgroundResource", if (d.working) R.drawable.widget_bg_green else R.drawable.widget_bg)
        setTextViewText(R.id.w_company, d.company)
        setTextViewText(R.id.w_status, d.status)
        setTextViewText(R.id.w_sub, d.sub)
        setOnClickPendingIntent(R.id.w_root, openApp(ctx))
    }

    fun large(ctx: Context, d: WidgetData) = RemoteViews(ctx.packageName, R.layout.widget_large).apply {
        setInt(R.id.w_root, "setBackgroundResource", if (d.working) R.drawable.widget_bg_green else R.drawable.widget_bg)
        setTextViewText(R.id.w_company, d.company)
        setTextViewText(R.id.w_status, d.status)
        setTextViewText(R.id.w_sub, d.sub)
        setTextViewText(R.id.w_ot_today, d.otToday)
        setTextViewText(R.id.w_ot_week, d.otWeek)
        setTextViewText(R.id.w_ot_month, d.otMonth)
        setTextViewText(R.id.w_pay, d.pay)
        setTextViewText(R.id.w_leave, d.leave)
        setTextViewText(R.id.w_extra, d.extra)
        setViewVisibility(R.id.w_extra, if (d.extra.isBlank()) android.view.View.GONE else android.view.View.VISIBLE)
        setViewVisibility(R.id.w_btn_out, if (d.canCheckout) android.view.View.VISIBLE else android.view.View.GONE)
        setOnClickPendingIntent(R.id.w_btn_out, PendingIntent.getBroadcast(ctx, 101,
            Intent(ctx, LargeWidget::class.java).setAction(ACTION_CHECKOUT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        setOnClickPendingIntent(R.id.w_root, openApp(ctx))
    }

    /** 모든 위젯 갱신 (실패해도 앱 동작에 영향 없게) */
    fun updateAll(ctx: Context) {
        runCatching {
            val mgr = AppWidgetManager.getInstance(ctx)
            val smallIds = mgr.getAppWidgetIds(ComponentName(ctx, SmallWidget::class.java))
            val largeIds = mgr.getAppWidgetIds(ComponentName(ctx, LargeWidget::class.java))
            if (smallIds.isEmpty() && largeIds.isEmpty()) return
            val d = runCatching { compute(ctx) }.getOrElse {
                WidgetData("슬기로운 직장생활", "앱을 열어주세요", "정보를 불러오지 못했어요", false, "-", "-", "-", "", "", false)
            }
            smallIds.forEach { mgr.updateAppWidget(it, small(ctx, d)) }
            largeIds.forEach { mgr.updateAppWidget(it, large(ctx, d)) }
        }
    }

    /** 위젯 [지금 퇴근] */
    fun checkoutNow(ctx: Context) {
        val repo = Repo.get(ctx)
        val d = LocalDate.now()
        val c = repo.activeCompany() ?: return
        val r = repo.day(d, c.id)
        if (r?.checkOut != null && r.source == Source.MANUAL) return
        val now = LocalTime.now().withSecond(0).withNano(0)
        val rec = DayRecord(d, c.id, Status.WORK, hm(c.workStart), now, Source.MANUAL, "위젯에서 퇴근")
        repo.saveDay(rec)
        Scheduler.cancelPoll(ctx)
        Notif.checkout(ctx, rec, c, repo.settings)
        Voice.checkout(ctx)
    }
}

class SmallWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = Widgets.updateAll(ctx)
}

class LargeWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = Widgets.updateAll(ctx)
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Widgets.ACTION_CHECKOUT) {
            Notif.init(ctx)
            runCatching { Widgets.checkoutNow(ctx) }
            Widgets.updateAll(ctx)
            return
        }
        super.onReceive(ctx, intent)
    }
}
