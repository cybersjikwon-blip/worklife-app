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
    val ym = YearMonth.from(today)
    val data = remember(version, now) {
        val c = repo.currentCompany(); val s = repo.settings
        val stored = HashMap(repo.month(ym, c.id))
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        if (YearMonth.from(monday) != ym) stored.putAll(repo.month(YearMonth.from(monday), c.id))
        val rec = stored[today]
        val tracker = Engine.evaluate(ctx, today).tracker
        val live = if (rec?.checkOut == null) tracker?.liveOvertimeMin(now) ?: 0 else 0
        val sum = overtimeSummary(c, s, today, stored, live)
        val monthRecs = buildMonth(c, ym, stored.filterKeys { YearMonth.from(it) == ym }, today)
        val pay = calcPayroll(c, s, ym, monthRecs, today)
        HomeData(c, s, rec, tracker, live, sum, pay, monthRecs.filter { it.source == Source.NEEDS_CHECK })
    }
    val c = data.c
    val hol = HolidayCalendar(c).holidayName(today)

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BigHeader("슬기로운 직장생활", "${c.name} · ${today.monthValue}월 ${today.dayOfMonth}일 (${wd(today)})")

        // ── 경고/안내
        val upd = remember(version) { Updater.pending(ctx) }
        if (upd != null) SCard(onClick = goSettings) {
            Text("새 버전 ${upd.name}이 나왔어요", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text("눌러서 설정 > 앱 업데이트에서 설치하세요. 기록은 그대로 유지돼요.", fontSize = 14.sp)
        }
        if (!c.hasLocation) SCard(onClick = goSettings) {
            Text("회사 위치를 등록해 주세요", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
            Text("설정 > 회사 > '지금 위치를 회사로'를 누르면 퇴근이 자동으로 기록돼요.", fontSize = 14.sp)
        } else if (!ctx.hasFineLoc() || !ctx.hasBgLoc()) SCard(onClick = goSettings) {
            Text("위치 권한을 '항상 허용'으로 바꿔 주세요", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
            Text("앱을 닫아도 퇴근을 감지하려면 필요해요. 설정 > 권한 점검", fontSize = 14.sp)
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
            if (hol == null && data.rec?.status != Status.ABSENT && data.rec?.status != Status.LEAVE) {
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
                OtTile("이번 달", sm.monthMin, sm.monthPay, Modifier.weight(1f))
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
            CardTitle("${ym.monthValue}월 예상 급여")
            KV("지급 합계", won(data.pay.gross))
            KV("공제 합계", "-" + won(data.pay.totalDeduct))
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("실수령", fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(won(data.pay.net), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Text("오늘까지 기록 + 남은 근무일 정시 기준", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
        }
        Spacer(Modifier.height(24.dp))
    }
}

data class HomeData(val c: Company, val s: AppSettings, val rec: DayRecord?, val tracker: GeofenceTracker?,
                    val live: Int, val sum: OtSummary, val pay: Payslip, val needCheck: List<DayRecord>)

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

