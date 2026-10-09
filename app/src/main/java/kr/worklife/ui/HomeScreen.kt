package kr.worklife.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kr.worklife.bg.*
import kr.worklife.core.*
import kr.worklife.data.Repo
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit

@Composable
fun HomeScreen(version: Int, bump: () -> Unit, modifier: Modifier, goSettings: () -> Unit, goRecords: () -> Unit) {
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = LocalDateTime.now() } }

    val repo = Repo.get(ctx)
    val today = now.toLocalDate()
    val data = remember(version, now) {
        val c = repo.currentCompany(); val s = repo.settings
        val period = periodContaining(c, today)
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val from = if (monday.isBefore(period.start)) monday else period.start
        val stored = HashMap(repo.range(from, period.end, c.id))
        val rec = stored[today]
        val tracker = Engine.evaluate(ctx, today).tracker
        val workday = HolidayCalendar(c).isWorkday(today)
        val live = if (workday && rec?.checkOut == null && (rec == null || rec.status == Status.WORK)) tracker?.liveOvertimeMin(now) ?: 0 else 0
        val sum = overtimeSummary(c, s, today, stored, live)
        val monthRecs = buildPeriod(c, period, stored.filterKeys { period.contains(it) }, today)
        val pay = calcPayroll(c, s, period, monthRecs, today)
        // 다음 급여일 (지난 기간 급여가 아직 안 나왔으면 그 기간)
        val np = nextPayday(c, today)
        val npPay = if (np.label == period.label) pay
            else calcPayroll(c, s, np, buildPeriod(c, np, repo.period(np, c.id), today), today)
        val (ls, le) = leaveWindow(c, today)
        val leave = leaveSummary(c, today, repo.range(ls, le, c.id).values.toList())
        HomeData(c, s, rec, tracker, live, sum, pay, monthRecs.filter { it.source == Source.NEEDS_CHECK }, period, np, npPay, leave)
    }
    val c = data.c
    val hol = HolidayCalendar(c).holidayName(today)

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BigHeader("슬기로운 직장생활", "${c.name} · ${today.monthValue}월 ${today.dayOfMonth}일 (${wd(today)})")

        // ── 경고/안내
        val upd = remember(version) { Updater.pending(ctx) }
        if (upd != null) SCard(onClick = { UpdateState.show(upd) }) {
            Text("새 버전 ${upd.name}이 나왔어요", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text("눌러서 바로 업데이트하세요. 기록은 그대로 유지돼요.", fontSize = 14.sp)
        }
        if (!c.hasLocation) SCard(onClick = goSettings) {
            Text("회사 위치를 등록해 주세요", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
            Text("설정 > 회사 > '지금 위치를 회사로'를 누르면 퇴근이 자동으로 기록돼요.", fontSize = 14.sp)
        } else if (!ctx.hasFineLoc() || !ctx.hasBgLoc()) SCard(onClick = goSettings) {
            Text("위치 권한을 '항상 허용'으로 바꿔 주세요", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
            Text("앱을 닫아도 퇴근을 감지하려면 필요해요. 설정 > 권한 점검", fontSize = 14.sp)
        }
        // 휴일인데 회사 반경에 오래 머문 경우: 자동 기록 대신 제안
        val tr = data.tracker
        val fi = tr?.firstInside; val li = tr?.lastInside
        if (hol != null && data.rec == null && fi != null && li != null && java.time.Duration.between(fi, li).toMinutes() >= 60) SCard {
            Text("휴일인데 회사에 ${hmShort(java.time.Duration.between(fi, li).toMinutes().toInt())} 계셨어요", fontWeight = FontWeight.SemiBold, color = OtOrange)
            Text("${fi.toLocalTime().hhmm()} ~ ${li.toLocalTime().hhmm()} · 실제로 일하셨으면 휴일근무로 기록하세요. 회사 위치가 집으로 잡혀 있다면 설정에서 고쳐주세요.", fontSize = 14.sp)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    repo.saveDay(DayRecord(today, c.id, Status.HOLIDAY_WORK, fi.toLocalTime().withSecond(0).withNano(0),
                        li.toLocalTime().withSecond(0).withNano(0), Source.MANUAL, "휴일근무"))
                    bump()
                }) { Text("휴일근무로 기록") }
                OutlinedButton(onClick = goSettings) { Text("회사 위치 확인") }
            }
        }
        if (data.needCheck.isNotEmpty()) SCard(onClick = goRecords) {
            Text("퇴근 확인이 필요한 날 ${data.needCheck.size}일", fontWeight = FontWeight.SemiBold, color = OtOrange)
            Text(data.needCheck.joinToString(", ") { "${it.day.dayOfMonth}일" } + " — 눌러서 확인", fontSize = 14.sp)
        }

        // ── 오늘
        SCard {
            CardTitle("오늘")
            val (title, sub) = todayStatus(c, data, hol, now)
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(sub, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), modifier = Modifier.padding(top = 4.dp))
            if (hol == null && data.rec?.status != Status.ABSENT && data.rec?.status != Status.LEAVE && data.rec?.status != Status.HALF_LEAVE) {
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        repo.saveDay(DayRecord(today, c.id, Status.WORK, hm(c.workStart), LocalTime.now().withSecond(0).withNano(0), Source.MANUAL, "수동 퇴근"))
                        bump()
                    }, enabled = data.rec?.checkOut == null && now.toLocalTime().isAfter(hm(c.workStart))) { Text("지금 퇴근") }
                    OutlinedButton(onClick = {
                        pickTime(ctx, data.rec?.checkOut ?: hm(c.workEnd)) { t ->
                            repo.saveDay(DayRecord(today, c.id, Status.WORK, hm(c.workStart), t, Source.MANUAL, "수동 입력")); bump()
                        }
                    }) { Text("퇴근시각 입력") }
                }
            }
        }

        // ── 추가근무
        val sm = data.sum
        SCard {
            CardTitle("추가 근무", trailing = { Tag("시급 ${"%,d".format(data.s.hourly(c, today.year).toInt())}원 × ${c.overtimeRate}", OkGreen) })
            Row(Modifier.fillMaxWidth()) {
                OtTile("오늘", sm.todayMin, sm.todayPay, Modifier.weight(1f))
                OtTile("이번 주", sm.weekMin, sm.weekPay, Modifier.weight(1f))
                OtTile(if (c.periodStartDay <= 1) "이번 달" else "이번 기간", sm.monthMin, sm.monthPay, Modifier.weight(1f))
            }
            val ratio = (sm.weekMin.toFloat() / sm.weekLimitMin).coerceIn(0f, 1f)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("주 연장 한도", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("${hmShort(sm.weekMin)} / ${sm.weekLimitMin / 60}시간", fontSize = 13.sp,
                    color = if (ratio >= .85f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
            LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                color = if (ratio >= .85f) MaterialTheme.colorScheme.error else OtOrange)
            if (data.live > 0) Text("※ 오늘 값은 퇴근 전 실시간 추정치예요", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
        }

        // ── 만근
        SCard {
            CardTitle("만근 현황", trailing = {
                Tag(if (data.pay.bonus > 0) "만근수당 ${won(data.pay.bonus)} ${data.pay.bonusStatus}" else data.pay.bonusStatus,
                    if (data.pay.bonus > 0) OkGreen else MaterialTheme.colorScheme.error)
            })
            Text("출근 ${sm.attendedDays} / ${sm.workdaysSoFar}일 (이번 달 근무일 ${sm.workdaysTotal}일)", fontSize = 15.sp)
            LinearProgressIndicator(progress = { if (sm.workdaysTotal == 0) 0f else sm.workdaysSoFar.toFloat() / sm.workdaysTotal },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp), color = OkGreen)
        }

        // ── 이번 달 예상 급여
        SCard {
            CardTitle("${data.period.label.monthValue}월분 예상 급여", trailing = { Text(data.period.rangeText(), fontSize = 13.sp) })
            KV("지급 합계", won(data.pay.gross))
            KV("공제 합계", "-" + won(data.pay.totalDeduct))
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("실수령", fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(won(data.pay.net), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Text("오늘까지 기록 + 남은 근무일 정시 기준 · 지급 ${data.period.payDate.monthValue}/${data.period.payDate.dayOfMonth}",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
        }

        // ── 다음 급여일
        SCard {
            val np = data.nextPay
            val dday = ChronoUnit.DAYS.between(today, np.payDate)
            CardTitle("다음 급여일", trailing = { Tag(if (dday == 0L) "오늘!" else "D-$dday", if (dday <= 3) OkGreen else MaterialTheme.colorScheme.primary) })
            Text("${np.payDate.monthValue}월 ${np.payDate.dayOfMonth}일 (${wd(np.payDate)})", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("${np.label.monthValue}월분 (${np.rangeText()}) · 예상 실수령 ${won(data.nextPayslip.net)}", fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp))
        }

        // ── 연차
        val lv = data.leave
        SCard(onClick = goRecords) {
            CardTitle("연차", trailing = {
                Tag(if (lv.auto) "자동 계산" else "직접 입력", MaterialTheme.colorScheme.primary)
            })
            Row(Modifier.fillMaxWidth()) {
                LeaveTile("남은", lv.remaining, if (lv.remaining < 0) MaterialTheme.colorScheme.error else OkGreen, Modifier.weight(1f))
                LeaveTile("사용", lv.used, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                LeaveTile("예정", lv.planned, OtOrange, Modifier.weight(1f))
                LeaveTile("전체", lv.total, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            }
            Text("${lv.yearStart} ~ ${lv.yearEnd}" + (if (lv.firstYear) " · 입사 1년 미만 (매월 1일씩 발생)" else "") +
                    (if (lv.remaining < 0) " · 연차를 초과했어요" else ""), fontSize = 12.sp,
                color = if (lv.remaining < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = .55f),
                modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

data class HomeData(val c: Company, val s: AppSettings, val rec: DayRecord?, val tracker: GeofenceTracker?,
                    val live: Int, val sum: OtSummary, val pay: Payslip, val needCheck: List<DayRecord>,
                    val period: PayPeriod, val nextPay: PayPeriod, val nextPayslip: Payslip, val leave: LeaveSummary)

fun days(v: Double): String = if (v % 1.0 == 0.0) "${v.toInt()}일" else "${"%.1f".format(v)}일"

@Composable
private fun LeaveTile(label: String, v: Double, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        Text(days(v), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun OtTile(label: String, min: Int, pay: Int, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        Text(hmShort(min), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = if (min > 0) OtOrange else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 4.dp))
        Text(won(pay), fontSize = 13.sp)
    }
}

private fun todayStatus(c: Company, d: HomeData, hol: String?, now: LocalDateTime): Pair<String, String> {
    val r = d.rec
    val t = now.toLocalTime()
    val start = hm(c.workStart); val end = hm(c.workEnd)
    return when {
        r?.status == Status.ABSENT -> "결근" to "만근수당이 지급되지 않아요"
        r?.status == Status.LEAVE -> "연차" to "오늘은 쉬어요"
        r?.status == Status.HALF_LEAVE -> "반차" to "${r?.checkIn?.hhmm() ?: "-"} ~ ${r?.checkOut?.hhmm() ?: "-"}"
        r?.checkOut != null -> {
            val rr = r!!
            val ot = overtimeMin(c, rr)
            "퇴근 ${rr.checkOut!!.hhmm()}" to ("[${rr.source.label}] " + if (ot > 0) "연장 ${hmShort(ot)}" else "정시 퇴근") + (if (rr.note.isNotBlank()) " · ${rr.note}" else "")
        }
        hol != null -> "쉬는 날" to "$hol · 근무 체크 안 함"
        t.isBefore(start) -> "출근 전" to "${c.workStart} 출근"
        t.isBefore(end) -> "근무 중" to "정시(${c.workEnd})까지 ${hmShort(ChronoUnit.MINUTES.between(t, end).toInt())}"
        !c.hasLocation -> "정시 지남" to "회사 위치가 없어 퇴근 자동 감지가 꺼져 있어요"
        d.live > 0 -> "연장 근무 중 +${hmShort(d.live)}" to "회사를 ${c.radiusM.toInt()}m 벗어나면 자동으로 퇴근 기록"
        else -> "퇴근 감지 중" to (d.tracker?.lastInside?.let { "마지막 회사 확인 ${it.toLocalTime().hhmm()}" } ?: "위치 기록 대기 중")
    }
}

