package kr.worklife.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
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
fun HomeScreen(version: Int, bump: () -> Unit, modifier: Modifier, goSettings: () -> Unit, goRecords: () -> Unit, goFun: () -> Unit = {},
               goPay: () -> Unit = {}, goCard: () -> Unit = {}) {
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = LocalDateTime.now() } }

    val repo = Repo.get(ctx)
    val today = now.toLocalDate()
    // 퇴사 상태: 재직 중인 회사가 없으면 안내만
    val active = remember(version) { repo.activeCompany() }
    if (active == null) {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            BigHeader("슬기로운 직장생활", "${today.monthValue}월 ${today.dayOfMonth}일 (${wd(today)})")
            SCard(onClick = goSettings) {
                Text("재직 중인 회사가 없어요", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("위치 감지와 알림을 멈췄어요. 새 직장에 다니게 되면 설정 > 회사 이력 > [새 회사 등록]을 눌러주세요. 이전 기록과 급여 이력은 근무기록·급여 탭에서 볼 수 있어요.",
                    fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                Button(onClick = goSettings, Modifier.padding(top = 12.dp)) { Text("새 회사 등록하러 가기") }
            }
            Spacer(Modifier.height(24.dp))
        }
        return
    }
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
        // 카드 사용액 (카드를 쓰기 시작한 경우에만 '한눈에'에 표시)
        val card = runCatching {
            if (repo.cardTxCount() == 0) null else {
                val cp = repo.cardPeriod; val ppl = repo.cardPeople
                val (cs, ce) = cardSpan(cp, ppl, today)
                summarizeNow(repo.cardTxs(cs, ce), ppl, cp, repo.cardBcOnly, today).let { it.total to "${it.count}건 · ${cardRangeLabel(cp, today).substringBefore(' ')}" }
            }
        }.getOrNull()
        HomeData(c, s, rec, tracker, live, sum, pay, monthRecs.filter { it.source == Source.NEEDS_CHECK }, period, np, npPay, leave, card?.first, card?.second ?: "")
    }
    val c = data.c
    val hol = HolidayCalendar(c).holidayName(today)

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // ── 상단: 인사 + 회사·날짜, 오른쪽 🔊 / ⚙ (네이버페이 화면 형식)
        var voiceOn by remember { mutableStateOf(Voice.isOn(ctx)) }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .12f), androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center) { Text("🧑‍💼", fontSize = 24.sp) }
            Column(Modifier.weight(1f).padding(start = 12.dp).clickable(onClick = goSettings)) {
                Text(greeting(now), fontSize = 13.sp, color = HomeGreen, fontWeight = FontWeight.SemiBold)
                Text("${c.name} · ${today.monthValue}월 ${today.dayOfMonth}일 (${wd(today)}) ›", fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            IconCircle(if (voiceOn) "🔊" else "🔇", if (voiceOn) HomeGreen.copy(alpha = .15f) else MaterialTheme.colorScheme.onSurface.copy(alpha = .06f)) {
                voiceOn = !voiceOn; Voice.set(ctx, voiceOn)
            }
            IconCircle("⚙️", MaterialTheme.colorScheme.onSurface.copy(alpha = .06f), onClick = goSettings)
        }

        // ── 최초 입사일 확인 (앱을 재직 중간부터 쓰는 경우 연차·퇴직금이 틀어지지 않게)
        if (!c.startConfirmed) SCard(color = MaterialTheme.colorScheme.primaryContainer) {
            Text("최초 입사일을 확인해 주세요", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text("연차·퇴직금 계산에 필요해요. 앱을 설치한 날이 아니라 실제로 입사한 날이에요. 지금 입사일: ${c.startDate}", fontSize = 14.sp)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { repo.saveCompany(c.copy(startConfirmed = true)); bump() }) { Text("맞아요") }
                OutlinedButton(onClick = {
                    pickDate(ctx, c.startDate) { d -> repo.saveCompany(c.copy(startDate = d, startConfirmed = true)); bump() }
                }) { Text("입사일 고르기") }
            }
        }

        // ── 점심 메뉴 바로가기 (점심 30분 전 ~ 점심 끝)
        val nt = now.toLocalTime()
        if (hol == null && !nt.isBefore(hm(c.lunchStart).minusMinutes(30)) && nt.isBefore(hm(c.lunchEnd))) SCard(onClick = goFun) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("오늘 점심 뭐 먹지?", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Tag("메뉴 룰렛 돌리기 →", MaterialTheme.colorScheme.primary)
            }
        }

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
        // 휴일인데 회사에 머문 경우: 자동 기록 대신 제안 (나간 게 확인되면 그 시각까지)
        val tr = data.tracker
        val fi = tr?.firstInside; val li = tr?.lastInside
        val hEnd = tr?.checkout ?: li
        if (hol != null && data.rec == null && fi != null && hEnd != null && java.time.Duration.between(fi, hEnd).toMinutes() >= 30) SCard {
            val mins = java.time.Duration.between(fi, hEnd).toMinutes().toInt()
            val left = tr?.checkout != null
            Text(if (left) "휴일에 회사에서 ${hmShort(mins)} 계셨어요" else "휴일인데 회사에 계신 것 같아요 (${hmShort(mins)}째)",
                fontWeight = FontWeight.SemiBold, color = OtOrange)
            Text("${fi.toLocalTime().hhmm()} ~ " + (if (left) "${hEnd.toLocalTime().hhmm()} 나가심" else "지금 (마지막 확인 ${hEnd.toLocalTime().hhmm()})") +
                " · 실제로 일하셨으면 휴일근무로 기록하세요. 회사 위치가 집으로 잡혀 있다면 설정에서 고쳐주세요.", fontSize = 14.sp)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    val endT = if (left) hEnd.toLocalTime() else LocalTime.now()
                    repo.saveDay(DayRecord(today, c.id, Status.HOLIDAY_WORK, fi.toLocalTime().withSecond(0).withNano(0),
                        endT.withSecond(0).withNano(0), Source.MANUAL, "휴일근무"))
                    bump()
                }) { Text(if (left) "휴일근무로 기록" else "지금까지 휴일근무로 기록") }
                OutlinedButton(onClick = goSettings) { Text("회사 위치 확인") }
            }
        }
        if (data.needCheck.isNotEmpty()) SCard(onClick = goRecords) {
            Text("퇴근 확인이 필요한 날 ${data.needCheck.size}일", fontWeight = FontWeight.SemiBold, color = OtOrange)
            Text(data.needCheck.joinToString(", ") { "${it.day.dayOfMonth}일" } + " — 눌러서 확인", fontSize = 14.sp)
        }

        // ── 메인 카드 (진한 색, 근무 중이면 진한 녹색): [오늘 | 이번 주 | 이번 달] 탭
        val (title, sub) = todayStatus(c, data, hol, now)
        val field = Engine.isFieldWork(data.rec)
        val working = hol == null && (title == "근무 중" || title.startsWith("연장 근무 중") || title == "퇴근 감지 중" ||
            (field && now.toLocalTime().isBefore(hm(c.workEnd)) && now.toLocalTime().isAfter(hm(c.workStart))))
        val sm = data.sum
        val done = data.rec?.checkOut != null
        val canAct = hol == null && data.rec?.status != Status.ABSENT && data.rec?.status != Status.LEAVE && data.rec?.status != Status.HALF_LEAVE
        val checkoutNow = {
            repo.saveDay(DayRecord(today, c.id, Status.WORK, hm(c.workStart), LocalTime.now().withSecond(0).withNano(0), Source.MANUAL, "수동 퇴근"))
            Scheduler.cancelPoll(ctx); Voice.checkout(ctx); bump()
        }
        val pickCheckout = {
            pickTime(ctx, data.rec?.checkOut ?: hm(c.workEnd)) { t ->
                repo.saveDay(DayRecord(today, c.id, Status.WORK, hm(c.workStart), t, Source.MANUAL, "수동 입력")); bump()
            }
        }
        var mainTab by remember { mutableIntStateOf(0) }
        val bg by androidx.compose.animation.animateColorAsState(if (working) Color(0xFF0E5C3F) else Color(0xFF22262D),
            androidx.compose.animation.core.tween(600), label = "main")
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
            .background(bg, androidx.compose.foundation.shape.RoundedCornerShape(26.dp)).padding(bottom = 18.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                listOf("오늘", "이번 주", if (c.periodStartDay <= 1) "이번 달" else "이번 기간").forEachIndexed { i, l ->
                    Column(Modifier.weight(1f).clickable { mainTab = i }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(l, fontSize = 15.sp, fontWeight = if (mainTab == i) FontWeight.Bold else FontWeight.Normal,
                            color = if (mainTab == i) Color.White else Color.White.copy(alpha = .45f))
                        Box(Modifier.padding(top = 6.dp).height(3.dp).width(64.dp).background(if (mainTab == i) Color.White else Color.Transparent))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(width = 38.dp, height = 44.dp).background(HomeGreen, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center) { Text(when (mainTab) { 0 -> "⏰"; 1 -> "📅"; else -> "🗓" }, fontSize = 18.sp) }
                        Column(Modifier.padding(start = 10.dp)) {
                            when (mainTab) {
                                0 -> {
                                    Text(if (working) (if (field) "외근 중" else "근무 중") else "오늘", fontSize = 13.sp, color = Color.White.copy(alpha = .75f))
                                    Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                                }
                                1 -> {
                                    Text("이번 주 연장", fontSize = 13.sp, color = Color.White.copy(alpha = .75f))
                                    Text(hmShort(sm.weekMin), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                                else -> {
                                    Text("${data.period.rangeText()} 연장", fontSize = 13.sp, color = Color.White.copy(alpha = .75f))
                                    Text(hmShort(sm.monthMin), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        }
                    }
                    Text(when (mainTab) {
                        0 -> sub
                        1 -> "주 한도 ${sm.weekLimitMin / 60}시간 중 ${(sm.weekMin * 100 / sm.weekLimitMin.coerceAtLeast(1))}% 사용"
                        else -> "출근 ${sm.attendedDays}/${sm.workdaysSoFar}일 · ${if (data.pay.bonus > 0) "만근 예정" else data.pay.bonusStatus}"
                    }, fontSize = 12.sp, color = Color.White.copy(alpha = .6f), maxLines = 2, modifier = Modifier.padding(top = 8.dp))
                    // 점심 외출 표시
                    if (mainTab == 0) {
                        data.tracker?.outings?.filter { it.lunch }?.forEach { o ->
                            Text("점심 외출 ${o.from.toLocalTime().hhmm()}~${o.to.toLocalTime().hhmm()}" + (if (o.overMin > 0) " (${o.overMin}분 초과)" else ""),
                                fontSize = 12.sp, color = if (o.overMin > 0) Color(0xFFFFB86B) else Color.White.copy(alpha = .6f))
                        }
                        data.tracker?.outsideSince?.let { os ->
                            if (hol == null && data.rec == null && c.inLunch(os.toLocalTime()))
                                Text("점심 외출 중 (${os.toLocalTime().hhmm()}~)", fontSize = 12.sp, color = Color(0xFF8FE3B5))
                        }
                    }
                }
                // 오른쪽 흰 상자: 핵심 숫자
                Column(Modifier.padding(start = 10.dp).size(112.dp).background(Color.White, androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).padding(10.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    val (k, v, w) = when (mainTab) {
                        0 -> Triple("오늘 연장", hmShort(sm.todayMin), won(sm.todayPay))
                        1 -> Triple("이번 주", hmShort(sm.weekMin), won(sm.weekPay))
                        else -> Triple("예상 실수령", "%,d".format(data.pay.net / 10000) + "만", won(sm.monthPay) + " 연장")
                    }
                    Text(k, fontSize = 11.sp, color = Color(0xFF6B7280))
                    Text(v, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111827), maxLines = 1, modifier = Modifier.padding(vertical = 2.dp))
                    Text(w, fontSize = 11.sp, color = HomeGreen, maxLines = 1)
                }
            }
            // 알약 버튼 (네이버페이 '카드 변경하기' 자리)
            if (mainTab == 0 && canAct) Row(Modifier.padding(horizontal = 18.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(if (done) "퇴근 시각 수정" else "지금 퇴근", Modifier.weight(1f), enabled = done || now.toLocalTime().isAfter(hm(c.workStart))) {
                    if (done) pickCheckout() else checkoutNow()
                }
                if (!done) PillButton("퇴근 시각", Modifier.weight(1f)) { pickCheckout() }
                else if (data.rec?.status == Status.WORK && !field) PillButton("퇴근 취소", Modifier.weight(1f)) { Engine.resumeWork(ctx); Tracker.oneShot(ctx) { bump() }; bump() }
            } else if (mainTab != 0) Row(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                PillButton("근무기록 보기", Modifier.fillMaxWidth()) { goRecords() }
            }
        }

        // ── 아이콘 바로가기 (5×2)
        SCard(pad = 14.dp) {
            val items = listOf(
                Triple("📅", "근무기록", goRecords), Triple("💰", "급여", goPay), Triple("💳", "카드", goCard),
                Triple("🌴", "연차", goRecords), Triple("🎡", "점심 룰렛", goFun),
                Triple("⏱", "퇴근 시각", { if (canAct) pickCheckout() }),
                Triple("↩️", "퇴근 취소", { if (done && !field) { Engine.resumeWork(ctx); Tracker.oneShot(ctx) { bump() }; bump() } }),
                Triple("🚗", if (field) "외근 취소" else "오늘 외근", { if (field) { Engine.setFieldWork(ctx, false); bump() } else if (data.rec == null && hol == null) { Engine.setFieldWork(ctx, true); bump() } }),
                Triple("📍", "위치 확인", { Tracker.oneShot(ctx) { bump() }; bump() }),
                Triple("⚙️", "설정", goSettings))
            items.chunked(5).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    row.forEach { (ic, label, act) ->
                        Column(Modifier.weight(1f).clickable { act() }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .05f), androidx.compose.foundation.shape.RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center) { Text(ic, fontSize = 22.sp) }
                            Text(label, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }

        // ── 한눈에 (가로로 넘기는 색 카드)
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("한눈에 보기", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("급여 ›", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f), modifier = Modifier.clickable(onClick = goPay))
        }
        val np = data.nextPay
        val dday = ChronoUnit.DAYS.between(today, np.payDate)
        val lv = data.leave
        val glance = listOfNotNull(
            Triple("💵", if (dday == 0L) "오늘 급여일!" else "급여일 D-$dday", "${np.payDate.monthValue}/${np.payDate.dayOfMonth} · 예상 ${won(data.nextPayslip.net)}") to goPay,
            Triple("⏱", "이번 주 연장 ${hmShort(sm.weekMin)}", "+${won(sm.weekPay)}") to goRecords,
            Triple("📈", "이번 달 연장 ${hmShort(sm.monthMin)}", "+${won(sm.monthPay)}") to goRecords,
            data.cardTotal?.let { Triple("💳", "카드 ${won(it.toInt())}", data.cardLabel) to goCard },
            Triple("🌴", "남은 연차 ${days(lv.remaining)}", "사용 ${days(lv.used)} / 전체 ${days(lv.total)}") to goRecords,
            Triple("🏅", if (data.pay.bonus > 0) "만근 예정" else "만근 ${data.pay.bonusStatus}", "출근 ${sm.attendedDays}/${sm.workdaysSoFar}일") to goRecords)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            glance.forEachIndexed { i, (t, act) ->
                val col = listOf(Color(0xFF14B866), Color(0xFF0FA968), Color(0xFF2E9E6B), Color(0xFF1C8C7A), Color(0xFF3A9D4F), Color(0xFF4B8F3C))[i % 6]
                Column(Modifier.width(168.dp).height(108.dp).background(col, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                    .clickable { act() }.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Box(Modifier.size(32.dp).background(Color.White.copy(alpha = .9f), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                        Text(t.first, fontSize = 16.sp)
                    }
                    Column {
                        Text(t.second, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                        Text(t.third, fontSize = 12.sp, color = Color.White.copy(alpha = .85f), maxLines = 1)
                    }
                }
            }
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

        // ── 연차
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
                    val period: PayPeriod, val nextPay: PayPeriod, val nextPayslip: Payslip, val leave: LeaveSummary,
                    val cardTotal: Long? = null, val cardLabel: String = "")

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
        Engine.isFieldWork(r) -> "외근" to "위치로 판정하지 않아요 · 정시 ${c.workEnd} 퇴근으로 기록 (늦게 끝나면 [퇴근시각 입력])"
        r?.checkOut != null -> {
            val rr = r!!
            val ot = overtimeMin(c, rr)
            val over = rr.checkOut!!.minOfDay() - hm(c.workEnd).minOfDay()
            // 기준이 되는 정시를 같이 보여줘서 설정이 틀렸는지 바로 보이게
            val what = when {
                ot > 0 -> "연장 ${hmShort(ot)} (정시 ${c.workEnd})"
                over > 0 -> "정시 ${c.workEnd} + ${over}분 · 연장 단위 ${c.overtimeUnitMin}분 미만"
                over < 0 -> "정시 ${c.workEnd}보다 ${hmShort(-over)} 일찍"
                else -> "정시(${c.workEnd}) 퇴근"
            }
            "퇴근 ${rr.checkOut.hhmm()}" to ("[${rr.source.label}] $what") + (if (rr.note.isNotBlank()) " · ${rr.note}" else "")
        }
        hol != null -> "쉬는 날" to "$hol · 근무 체크 안 함"
        t.isBefore(start) -> "출근 전" to "${c.workStart} 출근"
        t.isBefore(end) -> "근무 중" to "정시(${c.workEnd})까지 ${hmShort(ChronoUnit.MINUTES.between(t, end).toInt())}"
        !c.hasLocation -> "정시 지남" to "회사 위치가 없어 퇴근 자동 감지가 꺼져 있어요"
        d.live > 0 -> "연장 근무 중 +${hmShort(d.live)}" to "회사를 ${c.radiusM.toInt()}m 벗어나면 자동으로 퇴근 기록"
        else -> "퇴근 감지 중" to (d.tracker?.lastInside?.let { "마지막 회사 확인 ${it.toLocalTime().hhmm()}" } ?: "위치 기록 대기 중")
    }
}


private val HomeGreen = Color(0xFF03C75A)

private fun greeting(now: LocalDateTime): String = when (now.hour) {
    in 4..8 -> "좋은 아침이에요 ☀️"
    in 9..11 -> "오늘도 힘내세요 💪"
    in 12..13 -> "점심 맛있게 드세요 🍚"
    in 14..17 -> "조금만 더 힘내요!"
    in 18..21 -> "오늘도 수고 많으셨어요 🌙"
    else -> "편안한 밤 되세요 ✨"
}

@Composable
private fun IconCircle(icon: String, bg: Color, onClick: () -> Unit) {
    Box(Modifier.padding(start = 6.dp).size(40.dp).background(bg, androidx.compose.foundation.shape.CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) { Text(icon, fontSize = 18.sp) }
}

/** 진한 카드 위의 알약 모양 버튼 */
@Composable
private fun PillButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.height(44.dp)
        .border(1.dp, Color.White.copy(alpha = if (enabled) .35f else .15f), androidx.compose.foundation.shape.RoundedCornerShape(50))
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = if (enabled) 1f else .4f))
    }
}
