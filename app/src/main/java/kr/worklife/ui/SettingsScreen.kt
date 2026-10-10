package kr.worklife.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import kr.worklife.bg.*
import kr.worklife.core.*
import kr.worklife.data.Repo
import java.time.LocalDate

@Composable
fun SettingsScreen(version: Int, bump: () -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val repo = Repo.get(ctx)
    val companies = remember(version) { repo.companies }
    var editId by remember { mutableStateOf(repo.currentCompany().id) }
    val company = companies.firstOrNull { it.id == editId } ?: repo.currentCompany()
    var moving by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BigHeader("설정")
        key(company.id, version) { CompanyForm(company, bump) }

        SCard {
            CardTitle("회사 이력")
            companies.sortedByDescending { it.startDate }.forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, fontWeight = FontWeight.SemiBold)
                        Text("${c.startDate} ~ ${c.endDate ?: "재직 중"}", fontSize = 12.sp)
                    }
                    if (c.id == company.id) Tag("편집 중", MaterialTheme.colorScheme.primary)
                    else TextButton(onClick = { editId = c.id }) { Text("편집") }
                }
            }
            Button(onClick = { moving = true }, Modifier.padding(top = 8.dp)) { Text("회사 옮기기 (이직)") }
            Text("이직해도 이전 회사의 근무기록·급여 이력은 그대로 남아요.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
        }

        key(version) { PersonalForm(bump) }
        UpdateCard(version, bump)
        PermissionCard(version, bump)

        SCard {
            CardTitle("앱 정보")
            Text("슬기로운 직장생활 1.0\n공휴일 표: ${kr.worklife.core.HolidayTable.FIRST_YEAR}~${kr.worklife.core.HolidayTable.LAST_YEAR}년 (임시공휴일은 회사 휴무일에 추가)\n" +
                    "최저시급 표: " + repo.settings.minWageTable.toSortedMap().entries.joinToString(", ") { "${it.key}년 ${"%,d".format(it.value)}원" },
                fontSize = 13.sp)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (moving) MoveDialog(onDismiss = { moving = false }) { name, start ->
        val nc = repo.moveCompany(name, start)
        editId = nc.id; moving = false
        Scheduler.scheduleAll(ctx); Geo.register(ctx); bump()
        Toast.makeText(ctx, "새 회사 '${name}'를 등록했어요. 위치와 근무조건을 확인하세요.", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun CompanyForm(c: Company, bump: () -> Unit) {
    val ctx = LocalContext.current
    val repo = Repo.get(ctx)
    var name by remember { mutableStateOf(c.name) }
    var start by remember { mutableStateOf(c.startDate) }
    var end by remember { mutableStateOf(c.endDate) }
    var lat by remember { mutableStateOf(if (c.hasLocation) c.lat.toString() else "") }
    var lon by remember { mutableStateOf(if (c.hasLocation) c.lon.toString() else "") }
    var radius by remember { mutableStateOf(c.radiusM.toInt().toString()) }
    var ws by remember { mutableStateOf(hm(c.workStart)) }
    var we by remember { mutableStateOf(hm(c.workEnd)) }
    var lunch by remember { mutableStateOf(c.lunchMin.toString()) }
    var base by remember { mutableStateOf(c.baseSalary.toString()) }
    var linked by remember { mutableStateOf(c.baseLinkedToMinWage) }
    var rate by remember { mutableStateOf(c.overtimeRate.toString()) }
    var unit by remember { mutableStateOf(c.overtimeUnitMin.toString()) }
    var bonus by remember { mutableStateOf(c.fullAttendanceBonus.toString()) }
    var weekend by remember { mutableStateOf(c.weekendDays) }
    var xh by remember { mutableStateOf(c.extraHolidays.sorted().joinToString(", ")) }
    var xw by remember { mutableStateOf(c.extraWorkdays.sorted().joinToString(", ")) }
    var locating by remember { mutableStateOf(false) }
    var pStart by remember { mutableStateOf(c.periodStartDay.toString()) }
    var payDay by remember { mutableStateOf(if (c.payDay <= 0) "" else c.payDay.toString()) }
    var payLast by remember { mutableStateOf(c.payDay <= 0) }
    var payOff by remember { mutableStateOf(c.payMonthOffset) }
    var payAdj by remember { mutableStateOf(c.payAdjust) }
    var prorate by remember { mutableStateOf(c.prorateBy) }
    var bonusPartial by remember { mutableStateOf(c.bonusOnPartial) }
    var leaveAuto by remember { mutableStateOf(c.annualLeaveDays < 0) }
    var leaveDays by remember { mutableStateOf(if (c.annualLeaveDays < 0) "15" else c.annualLeaveDays.toString()) }
    var leaveBasis by remember { mutableStateOf(c.leaveBasis) }
    var carry by remember { mutableStateOf(if (c.leaveCarryover == 0.0) "" else days(c.leaveCarryover).removeSuffix("일")) }

    SCard {
        CardTitle("회사", trailing = { if (c.endDate == null) Tag("현재 회사", OkGreen) })
        Field("회사 이름", name, { name = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickDate(ctx, start) { start = it } }, Modifier.weight(1f)) { Text("입사 $start", fontSize = 13.sp) }
            OutlinedButton(onClick = { pickDate(ctx, end ?: LocalDate.now()) { end = it } }, Modifier.weight(1f)) {
                Text(if (end == null) "퇴사일 없음" else "퇴사 $end", fontSize = 13.sp)
            }
        }
        if (end != null) TextButton(onClick = { end = null }) { Text("퇴사일 지우기") }

        Text("근무지 위치", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Button(onClick = {
            if (!ctx.hasFineLoc()) { Toast.makeText(ctx, "아래 '권한 점검'에서 위치 권한을 먼저 허용하세요", Toast.LENGTH_LONG).show(); return@Button }
            locating = true
            Geo.current(ctx) { l ->
                locating = false
                if (l == null) Toast.makeText(ctx, "위치를 못 잡았어요. GPS를 켜고 실외/창가에서 다시 시도하세요", Toast.LENGTH_LONG).show()
                else {
                    lat = "%.6f".format(l.latitude); lon = "%.6f".format(l.longitude)
                    Toast.makeText(ctx, "현재 위치(오차 ${l.accuracy.toInt()}m)를 넣었어요. 저장을 눌러주세요", Toast.LENGTH_LONG).show()
                }
            }
        }, enabled = !locating) { Text(if (locating) "위치 찾는 중…" else "지금 위치를 회사로") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("위도", lat, { lat = it }, number = true, modifier = Modifier.weight(1f))
            Field("경도", lon, { lon = it }, number = true, modifier = Modifier.weight(1f))
        }
        Field("퇴근 판정 반경(m)", radius, { radius = it }, number = true, hint = "회사 건물·주차장이 다 들어가게 (기본 100)")
        if (lat.toDoubleOrNull() != null && lon.toDoubleOrNull() != null) TextButton(onClick = {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(name)})"))) }
        }) { Text("지도에서 확인") }
        val lw = remember(c) { Scheduler.learned(ctx, c) }
        val cnt = remember(c) { runCatching { repo.recentAutoCheckouts(c.id).size }.getOrDefault(0) }
        Text(if (lw != null) "학습된 퇴근 시간대 ${lw.from.hhmm()}~${lw.to.hhmm()} (최근 ${lw.samples}회): 이 시간엔 1분마다, 그 외엔 정시 이후 5분마다 위치 확인"
             else "정시 이후 5분마다 위치 확인 · 자동 퇴근이 5회 쌓이면 퇴근 시간대를 학습해 그 시간엔 1분마다 확인해요 (현재 ${cnt}회)",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))

        Text("근무 시간", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickTime(ctx, ws) { ws = it } }, Modifier.weight(1f)) { Text("출근 ${ws.hhmm()}") }
            OutlinedButton(onClick = { pickTime(ctx, we) { we = it } }, Modifier.weight(1f)) { Text("퇴근 ${we.hhmm()}") }
        }
        Field("휴게(분)", lunch, { lunch = it }, number = true)
        Text("쉬는 요일", fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..7).forEach { d ->
                FilterChip(d in weekend, { weekend = if (d in weekend) weekend - d else weekend + d },
                    label = { Text("월화수목금토일"[d - 1].toString()) })
            }
        }
        Field("회사 휴무일 (임시공휴일 등)", xh, { xh = it }, hint = "예) 2026-12-31, 2027-01-02")
        Field("휴일이지만 근무하는 날", xw, { xw = it }, hint = "예) 2026-10-10")

        Text("급여", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        SwitchRow("기본급 = 그 해 최저시급 × 209 (자동 인상)", linked) { linked = it }
        if (!linked) Field("기본급(원)", base, { base = it }, number = true)
        Field("연장 배율", rate, { rate = it }, number = true, hint = "요청 기준 1.0 · 5인 이상 사업장 법정 1.5")
        Field("연장 인정 단위(분)", unit, { unit = it }, number = true, hint = "30 → 15:29 퇴근은 0분, 15:30은 30분")
        Field("만근수당(원)", bonus, { bonus = it }, number = true)

        Text("급여 산정기간 · 급여일", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Field("산정기간 시작일 (1~28)", pStart, { pStart = it.filter { ch -> ch.isDigit() }.take(2) }, number = true,
            hint = "1 → 매월 1일~말일 · 21 → 전월 21일~당월 20일")
        SwitchRow("급여일이 말일", payLast) { payLast = it }
        if (!payLast) Field("급여일 (매월 n일)", payDay, { payDay = it.filter { ch -> ch.isDigit() }.take(2) }, number = true)
        Text("지급하는 달", fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(payOff == 0, { payOff = 0 }, label = { Text("기간 끝난 달") })
            FilterChip(payOff == 1, { payOff = 1 }, label = { Text("다음 달") })
        }
        Text("급여일이 휴일이면", fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("before" to "앞당김", "after" to "미룸", "none" to "그대로").forEach { (k, v) ->
                FilterChip(payAdj == k, { payAdj = k }, label = { Text(v) })
            }
        }
        // 미리보기: 입력값으로 이번 기간 계산
        val preview = runCatching {
            val tmp = c.copy(periodStartDay = pStart.toIntOrNull()?.coerceIn(1, 28) ?: 1,
                payDay = if (payLast) 0 else payDay.toIntOrNull()?.coerceIn(1, 31) ?: 10, payMonthOffset = payOff, payAdjust = payAdj)
            val pr = periodContaining(tmp, LocalDate.now())
            "이번 기간: ${pr.label.monthValue}월분 ${pr.rangeText()} → 급여일 ${pr.payDate.monthValue}/${pr.payDate.dayOfMonth}(${wd(pr.payDate)})"
        }.getOrDefault("")
        Text(preview, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))

        Text("중도 입사·퇴사", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Text("기본급 일할 계산 기준", fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(prorate == "calendar", { prorate = "calendar" }, label = { Text("달력일수") })
            FilterChip(prorate == "workdays", { prorate = "workdays" }, label = { Text("근무일수") })
        }
        SwitchRow("중도 입·퇴사 기간도 개근하면 만근수당 지급", bonusPartial) { bonusPartial = it }
        Text("입사월은 1일 입사가 아니면 국민연금·건강보험이 다음 달부터 빠져요 (자동 반영).", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))

        Text("연차", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        SwitchRow("근로기준법대로 자동 계산 (1년 미만 월 1일, 이후 15일+)", leaveAuto) { leaveAuto = it }
        if (!leaveAuto) Field("연간 연차 일수", leaveDays, { leaveDays = it.filter { ch -> ch.isDigit() }.take(2) }, number = true)
        Text("연차 기준일", fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(leaveBasis == "hire", { leaveBasis = "hire" }, label = { Text("입사일 기준") })
            FilterChip(leaveBasis == "calendar", { leaveBasis = "calendar" }, label = { Text("1월 1일 기준") })
        }
        Field("작년에서 이월된 연차 (일)", carry, { carry = it }, number = true, hint = "없으면 비워두세요 · 반일은 0.5")

        /** 입력값 → 새 회사 설정. 오류가 있으면 err에 담음 */
        fun build(err: MutableList<String>): Company {
            val la = lat.toDoubleOrNull(); val lo = lon.toDoubleOrNull()
            if ((lat.isNotBlank() || lon.isNotBlank()) && (la == null || lo == null || la !in -90.0..90.0 || lo !in -180.0..180.0)) err += "위도/경도"
            fun dates(s: String) = s.split(",", " ", "\n").map { it.trim() }.filter { it.isNotEmpty() }
                .map { ds -> runCatching { LocalDate.parse(ds).toString() }.getOrElse { err += "날짜 '$ds'"; "" } }.filter { it.isNotEmpty() }.toSet()
            val xhs = dates(xh); val xws = dates(xw)
            if (!we.isAfter(ws)) err += "출퇴근 시각"
            if (end != null && end!!.isBefore(start)) err += "퇴사일"
            if (name.isBlank()) err += "회사 이름"
            if (!payLast && (payDay.toIntOrNull() ?: 0) !in 1..31) err += "급여일(1~31)"
            if ((pStart.toIntOrNull() ?: 0) !in 1..28) err += "산정기간 시작일(1~28)"
            if (carry.isNotBlank() && carry.toDoubleOrNull() == null) err += "이월 연차"
            val others = repo.companies.filter { it.id != c.id }
            if (others.any { o -> !start.isAfter(o.endDate ?: LocalDate.MAX) && !(end ?: LocalDate.MAX).isBefore(o.startDate) })
                err += "다른 회사와 재직기간 겹침"
            return c.copy(
                name = name.trim(), startDate = start, endDate = end, lat = la ?: 0.0, lon = lo ?: 0.0,
                radiusM = (radius.toDoubleOrNull() ?: 100.0).coerceIn(50.0, 2000.0),
                workStart = ws.hhmm(), workEnd = we.hhmm(), lunchMin = lunch.toIntOrNull()?.coerceIn(0, 240) ?: 60,
                baseSalary = base.filter { it.isDigit() }.toIntOrNull() ?: c.baseSalary, baseLinkedToMinWage = linked,
                overtimeRate = (rate.toDoubleOrNull() ?: 1.0).coerceIn(1.0, 3.0), overtimeUnitMin = unit.toIntOrNull()?.coerceIn(1, 60) ?: 30,
                fullAttendanceBonus = bonus.filter { it.isDigit() }.toIntOrNull() ?: 0, weekendDays = weekend,
                extraHolidays = xhs, extraWorkdays = xws,
                periodStartDay = pStart.toIntOrNull()?.coerceIn(1, 28) ?: 1,
                payDay = if (payLast) 0 else payDay.toIntOrNull()?.coerceIn(1, 31) ?: 10,
                payMonthOffset = payOff, payAdjust = payAdj, prorateBy = prorate, bonusOnPartial = bonusPartial,
                annualLeaveDays = if (leaveAuto) -1 else leaveDays.toIntOrNull()?.coerceIn(0, 60) ?: 15,
                leaveBasis = leaveBasis, leaveCarryover = carry.toDoubleOrNull()?.coerceIn(0.0, 60.0) ?: 0.0)
        }

        /** 저장 + 근무시간·휴일이 바뀌었으면 근무기록을 새 기준으로 다시 맞춤 */
        fun save(auto: Boolean): Boolean {
            val err = mutableListOf<String>()
            val nc = build(err)
            if (err.isNotEmpty()) {
                Toast.makeText(ctx, (if (auto) "설정이 저장되지 않았어요 · " else "확인 필요: ") + err.joinToString(", "), Toast.LENGTH_LONG).show()
                return false
            }
            // 이미 저장된 값과 같으면 아무것도 안 함 (중복 저장·중복 안내 방지)
            val cur = repo.companies.firstOrNull { it.id == c.id } ?: c
            if (nc == cur) return true
            repo.saveCompany(nc)
            val scheduleChanged = nc.workStart != cur.workStart || nc.workEnd != cur.workEnd || nc.lunchMin != cur.lunchMin ||
                nc.weekendDays != cur.weekendDays || nc.extraHolidays != cur.extraHolidays || nc.extraWorkdays != cur.extraWorkdays ||
                nc.lat != cur.lat || nc.lon != cur.lon || nc.radiusM != cur.radiusM
            var fixed = 0
            if (scheduleChanged) {
                fixed = runCatching { repo.reapplyCompany(nc) }.getOrDefault(0)
                runCatching { Engine.settlePast(ctx); Engine.checkToday(ctx) }
            }
            repo.onboarded = true
            Scheduler.scheduleAll(ctx); Geo.register(ctx)
            Toast.makeText(ctx, (if (auto) "변경한 설정을 자동 저장했어요" else "저장했어요") +
                (if (fixed > 0) " · 근무기록 ${fixed}일을 새 기준으로 다시 맞췄어요" else ""), Toast.LENGTH_LONG).show()
            bump()
            return true
        }

        val dirty = runCatching { build(mutableListOf()) != c }.getOrDefault(true)
        // 저장 안 하고 다른 탭으로 가면 자동 저장 (근무시간만 바꾸고 저장을 깜빡하는 경우 대비)
        val latestSave = rememberUpdatedState({ if (dirty) save(auto = true) })
        DisposableEffect(Unit) { onDispose { latestSave.value() } }

        if (dirty) Text("저장하지 않은 변경이 있어요 (다른 탭으로 가면 자동 저장)", fontSize = 13.sp,
            color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 12.dp))
        Button(onClick = { save(auto = false) }, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("회사 설정 저장") }
    }
}

@Composable
private fun PersonalForm(bump: () -> Unit) {
    val ctx = LocalContext.current
    val repo = Repo.get(ctx)
    val s = repo.settings
    var mw by remember { mutableStateOf(s.minWageTable.toSortedMap().entries.joinToString(", ") { "${it.key}:${it.value}" }) }
    var mwOver by remember { mutableStateOf(if (s.minWageOverride > 0) s.minWageOverride.toString() else "") }
    var basisBase by remember { mutableStateOf(s.hourlyBasis == "base_div_209") }
    var hwRate by remember { mutableStateOf(s.holidayWorkRate.toString()) }
    var dep by remember { mutableStateOf(s.dependents.toString()) }
    var child by remember { mutableStateOf(s.children820.toString()) }
    var ratio by remember { mutableStateOf(s.withholdingRatio) }
    var nontax by remember { mutableStateOf(s.nontaxableMonthly.toString()) }
    var insBase by remember { mutableStateOf(if (s.insuranceBaseOverride > 0) s.insuranceBaseOverride.toString() else "") }
    var itax by remember { mutableStateOf(if (s.incomeTaxOverride >= 0) s.incomeTaxOverride.toString() else "") }
    var ins by remember { mutableStateOf(s.insOverride.toSortedMap().entries.joinToString("; ") { (y, r) -> "$y:${r.pension},${r.health},${r.ltc},${r.employment}" }) }
    var leaveOk by remember { mutableStateOf(s.leaveCountsAsAttendance) }
    var dedAbs by remember { mutableStateOf(s.deductAbsence) }
    var dedEarly by remember { mutableStateOf(s.deductEarlyLeave) }
    var exitMin by remember { mutableStateOf(s.exitConfirmMin.toString()) }
    var fast by remember { mutableStateOf(s.fastExitM.toInt().toString()) }
    var acc by remember { mutableStateOf(s.maxAccuracyM.toInt().toString()) }
    var cutoff by remember { mutableStateOf(hm(s.dayCutoff)) }

    SCard {
        CardTitle("급여·공제 (개인)")
        Field("연도별 최저시급", mw, { mw = it }, hint = "연도:금액, … 매년 8월 고시값 추가 (예: 2028:11000)")
        Field("최저시급 직접 지정(원)", mwOver, { mwOver = it }, number = true, hint = "비워두면 위 표에서 연도별 자동")
        SwitchRow("연장 시급을 기본급÷209로 계산 (끄면 최저시급)", basisBase) { basisBase = it }
        Field("휴일근무 배율", hwRate, { hwRate = it }, number = true)
        SwitchRow("연차는 만근에서 출근으로 인정", leaveOk) { leaveOk = it }
        SwitchRow("결근일 일급 공제", dedAbs) { dedAbs = it }
        SwitchRow("조퇴 시간 공제", dedEarly) { dedEarly = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("공제대상 가족(본인 포함)", dep, { dep = it }, number = true, modifier = Modifier.weight(1f))
            Field("8~20세 자녀", child, { child = it }, number = true, modifier = Modifier.weight(1f))
        }
        Text("원천징수 비율", fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(80, 100, 120).forEach { r -> FilterChip(ratio == r, { ratio = r }, label = { Text("$r%") }) }
        }
        Field("비과세 (식대 등, 원/월)", nontax, { nontax = it }, number = true)
        Field("보수월액 지정(원)", insBase, { insBase = it }, number = true, hint = "비우면 당월 급여 기준으로 4대보험 계산")
        Field("소득세 직접 입력(원)", itax, { itax = it }, number = true, hint = "명세서 소득세를 알면 입력, 비우면 자동")
        Field("보험요율 수정 (근로자 %)", ins, { ins = it }, hint = "연도:연금,건보,장기요양(건보료대비),고용 ; 예) 2027:5.0,3.6,13.2,0.9")

        Text("퇴근 자동 감지", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Field("반경 밖 머문 시간(분) 후 확정", exitMin, { exitMin = it }, number = true, hint = "편의점 다녀오기 정도는 무시 (기본 10)")
        Field("이 거리(m) 이상 멀어지면 즉시 확정", fast, { fast = it }, number = true, hint = "차로 출발하는 경우 (기본 1000)")
        Field("GPS 오차 허용(m)", acc, { acc = it }, number = true, hint = "이보다 부정확한 위치는 버림 (기본 50)")
        OutlinedButton(onClick = { pickTime(ctx, cutoff) { cutoff = it } }) { Text("하루 마감 ${cutoff.hhmm()}") }

        Button(onClick = {
            val table = runCatching {
                mw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.associate { p -> p.split(":").let { it[0].trim().toInt() to it[1].trim().toInt() } }
            }.getOrNull()
            val insMap = runCatching {
                ins.split(";").map { it.trim() }.filter { it.isNotEmpty() }.associate { p ->
                    val (y, v) = p.split(":"); val n = v.split(",").map { it.trim().toDouble() }
                    y.trim().toInt() to InsRates(n[0], n[1], n[2], n[3])
                }
            }.getOrNull()
            if (table == null || table.isEmpty() || insMap == null) {
                Toast.makeText(ctx, "최저시급 표 또는 보험요율 형식을 확인하세요", Toast.LENGTH_LONG).show(); return@Button
            }
            repo.settings = s.copy(minWageTable = table, minWageOverride = mwOver.toIntOrNull() ?: 0,
                hourlyBasis = if (basisBase) "base_div_209" else "min_wage", holidayWorkRate = hwRate.toDoubleOrNull() ?: 1.5,
                leaveCountsAsAttendance = leaveOk, deductAbsence = dedAbs, deductEarlyLeave = dedEarly,
                dependents = dep.toIntOrNull()?.coerceIn(1, 20) ?: 1, children820 = child.toIntOrNull()?.coerceIn(0, 10) ?: 0,
                withholdingRatio = ratio, nontaxableMonthly = nontax.filter { it.isDigit() }.toIntOrNull() ?: 0,
                insuranceBaseOverride = insBase.filter { it.isDigit() }.toIntOrNull() ?: 0,
                incomeTaxOverride = itax.filter { it.isDigit() }.toIntOrNull() ?: -1, insOverride = insMap,
                exitConfirmMin = exitMin.toIntOrNull()?.coerceIn(0, 60) ?: 10, fastExitM = fast.toDoubleOrNull()?.coerceIn(200.0, 20000.0) ?: 1000.0,
                maxAccuracyM = acc.toDoubleOrNull()?.coerceIn(5.0, 500.0) ?: 50.0, dayCutoff = cutoff.hhmm())
            Toast.makeText(ctx, "저장했어요", Toast.LENGTH_SHORT).show()
            bump()
        }, Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("급여·공제 설정 저장") }
    }
}

@Composable
private fun PermissionCard(version: Int, bump: () -> Unit) {
    val ctx = LocalContext.current
    val fineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { bump() }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) runCatching {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            Toast.makeText(ctx, "권한 > 위치 > '항상 허용'을 선택하세요", Toast.LENGTH_LONG).show()
        }
        Geo.register(ctx); bump()
    }
    @Suppress("UNUSED_VARIABLE") val v = version
    val pm = ctx.getSystemService(PowerManager::class.java)
    val notifOk = Build.VERSION.SDK_INT < 33 || ctx.hasPerm(Manifest.permission.POST_NOTIFICATIONS)
    val batteryOk = pm.isIgnoringBatteryOptimizations(ctx.packageName)
    val exactOk = Scheduler.canExact(ctx)

    SCard {
        CardTitle("권한 점검", trailing = { TextButton(onClick = bump) { Text("새로고침") } })
        PermRow("정확한 위치", ctx.hasFineLoc(), "허용") {
            fineLauncher.launch(buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray())
        }
        PermRow("위치 '항상 허용' (앱 꺼도 감지)", ctx.hasBgLoc(), "설정") {
            if (!ctx.hasFineLoc()) Toast.makeText(ctx, "'정확한 위치'를 먼저 허용하세요", Toast.LENGTH_SHORT).show()
            else bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        PermRow("알림 (퇴근 기록 알림)", notifOk, "허용") {
            if (Build.VERSION.SDK_INT >= 33) fineLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
        PermRow("정확한 알람 (정시에 감지 시작)", exactOk, "설정") {
            if (Build.VERSION.SDK_INT >= 31) runCatching {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
            }
        }
        PermRow("배터리 사용량 '제한 없음'", batteryOk, "설정") {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            }.onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
        }
        Text("갤럭시: 설정 > 배터리 > 백그라운드 사용 제한 > '절전 예외 앱'에 이 앱을 추가하면 더 확실해요. " +
                "'사용하지 않는 앱을 절전 상태로' 목록에 들어가 있으면 빼주세요.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun PermRow(label: String, ok: Boolean, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        if (ok) Tag("완료", OkGreen) else FilledTonalButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun MoveDialog(onDismiss: () -> Unit, onSave: (String, LocalDate) -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var start by remember { mutableStateOf(LocalDate.now()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("회사 옮기기") },
        text = {
            Column {
                Text("지금 회사는 입사일 전날로 퇴사 처리되고, 근무조건은 새 회사로 복사돼요. 위치는 새로 설정하세요.", fontSize = 13.sp)
                Field("새 회사 이름", name, { name = it })
                OutlinedButton(onClick = { pickDate(ctx, start) { start = it } }) { Text("입사일 $start") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim(), start) }, enabled = name.isNotBlank()) { Text("등록") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}


@Composable
private fun UpdateCard(version: Int, bump: () -> Unit) {
    val ctx = LocalContext.current
    val act = ctx as? android.app.Activity
    var repoName by remember { mutableStateOf(Updater.repo(ctx)) }
    var token by remember { mutableStateOf(Updater.token(ctx)) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val found = remember(version) { Updater.pending(ctx) }

    SCard {
        CardTitle("앱 업데이트", trailing = { Tag("현재 ${Updater.currentName(ctx)}", MaterialTheme.colorScheme.primary) })
        Field("GitHub 저장소 (아이디/저장소)", repoName, { repoName = it })
        Field("토큰 (비공개 저장소일 때만)", token, { token = it }, hint = "Contents 읽기 전용 토큰 · 이 폰에만 저장돼요")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                Updater.save(ctx, repoName, token)
                busy = true; status = "확인 중…"
                Thread {
                    val r = runCatching { Updater.check(ctx) }
                    val apply = Runnable {
                        busy = false
                        r.onSuccess { rel ->
                            status = if (rel == null) "최신 버전이에요 ✓" else "새 버전 ${rel.name} 있어요"
                            if (rel != null) UpdateState.show(rel)
                        }.onFailure { status = "⚠ ${it.message}" }
                        bump()
                    }
                    if (act != null) act.runOnUiThread(apply) else apply.run()
                }.start()
            }, enabled = !busy) { Text(if (busy) "확인 중…" else "업데이트 확인") }
            if (found != null) FilledTonalButton(onClick = { UpdateState.show(found) }) { Text("${found.name} 설치") }
        }
        if (status.isNotBlank()) Text(status, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        Text("GitHub에 수정본을 올리면 자동으로 새 릴리즈가 만들어지고, 앱이 하루 몇 번 확인해서 알려줘요. 기록은 그대로 유지돼요.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f), modifier = Modifier.padding(top = 8.dp))
    }
}
