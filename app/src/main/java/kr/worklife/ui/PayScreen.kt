package kr.worklife.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.worklife.core.*
import kr.worklife.data.PayHistory
import kr.worklife.data.Repo
import java.time.LocalDate
import java.time.YearMonth

/** 급여 이력: 확정·직접입력 스냅샷 + 기록이 있는 달의 미확정 계산값 (최근 36개월) */
fun payHistory(repo: Repo, today: LocalDate = LocalDate.now()): List<PayHistory> {
    val saved = repo.savedPays()
    val keys = saved.map { it.ym to it.companyId }.toSet()
    val now = YearMonth.from(today)
    val first = listOfNotNull(repo.firstRecordMonth(), saved.minOfOrNull { it.ym }).minOrNull() ?: now
    var ym = maxOf(first, now.minusMonths(35))
    val s = repo.settings
    val live = mutableListOf<PayHistory>()
    while (!ym.isAfter(now.plusMonths(1))) {
        for (c in repo.companiesIn(ym)) {
            if ((ym to c.id) in keys) continue
            val per = periodOf(c, ym)
            if (per.start.isAfter(today)) continue
            val p = calcPayroll(c, s, per, buildPeriod(c, per, repo.period(per, c.id), today), today)
            live += PayHistory(ym, c.id, c.name, p.gross, p.totalDeduct, p.net, if (per.contains(today)) "진행중" else "미확정", p.lines())
        }
        ym = ym.plusMonths(1)
    }
    return (saved + live).sortedWith(compareByDescending<PayHistory> { it.ym }.thenBy { it.companyId })
}

@Composable
fun PayScreen(version: Int, bump: () -> Unit, ym: YearMonth, setYm: (YearMonth) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val repo = Repo.get(ctx)
    var cid by remember { mutableStateOf<Long?>(null) }
    var manual by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BigHeader("급여")
        MonthNav(ym, setYm)
        val c = CompanyPicker(ym, cid) { cid = it.id }
        val today = LocalDate.now()
        val history = remember(version) { payHistory(repo, today) }

        if (c != null) {
            val per = remember(ym, c) { periodOf(c, ym) }
            PeriodLine(per)
            val snap = history.firstOrNull { it.ym == ym && it.companyId == c.id && (it.kind == "확정" || it.kind == "직접입력") }
            val p = remember(version, ym, c) { calcPayroll(c, repo.settings, per, buildPeriod(c, per, repo.period(per, c.id), today), today) }
            SCard {
                CardTitle(c.name, trailing = {
                    Tag(snap?.kind ?: if (per.contains(today)) "진행중" else if (per.start.isAfter(today)) "예정" else "미확정",
                        if (snap != null) OkGreen else OtOrange)
                })
                val lines = snap?.lines ?: p.lines()
                lines.forEach { l ->
                    if (l.label == "지급 합계" || l.label == "실수령액") HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    KV(l.label, won(l.amount), l.bold,
                        if (l.label == "실수령액") MaterialTheme.colorScheme.primary else if (l.amount < 0) MaterialTheme.colorScheme.onSurface.copy(alpha = .7f) else androidx.compose.ui.graphics.Color.Unspecified)
                }
                if (snap == null) {
                    Text("${ym.year}년 최저시급 ${"%,d".format(p.minWage)}원 · 계산 시급 ${"%,d".format(p.hourly.toInt())}원 · 연장 ${c.overtimeRate}배\n소득세는 간이세액표 산식 근사치예요.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f), modifier = Modifier.padding(top = 8.dp))
                    p.warnings.forEach { Text("⚠ $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (snap == null) {
                        Button(onClick = { repo.savePayslip(p); bump() }, enabled = !per.start.isAfter(today)) { Text("이 달 확정") }
                        OutlinedButton(onClick = { manual = true }) { Text("실제 금액 입력") }
                    } else {
                        OutlinedButton(onClick = { repo.deletePay(ym, c.id); bump() }) { Text("확정 취소(다시 계산)") }
                    }
                }
            }
            if (manual) ManualPayDialog(ym, c, onDismiss = { manual = false }) { g, d ->
                repo.savePay(ym, c.id, c.name, g, d, g - d, "직접입력",
                    listOf(PayLine("지급 합계", g, true), PayLine("공제 합계", -d, true), PayLine("실수령액", g - d, true)))
                manual = false; bump()
            }
        } else Text("이 달에 재직한 회사가 없어요", Modifier.padding(24.dp))

        // ── 급여 이력
        SCard {
            CardTitle("급여 이력", trailing = { Text("최근 ${minOf(12, history.size)}건", fontSize = 13.sp) })
            val chart = history.take(12).reversed()
            if (chart.isNotEmpty()) {
                val max = chart.maxOf { it.net }.coerceAtLeast(1)
                val min = (chart.minOf { it.net } * 0.97).toInt() // 차이가 보이도록 최소값 근처부터 그림
                val barColor = MaterialTheme.colorScheme.primary
                val estColor = MaterialTheme.colorScheme.primary.copy(alpha = .35f)
                Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                    val slot = size.width / chart.size
                    chart.forEachIndexed { i, h ->
                        val bh = size.height * (0.25f + 0.75f * (h.net - min).coerceAtLeast(0) / (max - min).coerceAtLeast(1))
                        drawRoundRect(if (h.kind == "확정" || h.kind == "직접입력") barColor else estColor,
                            topLeft = Offset(i * slot + slot * .2f, size.height - bh), size = Size(slot * .6f, bh), cornerRadius = CornerRadius(8f, 8f))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    chart.forEach { Text("${it.ym.monthValue}월", Modifier.weight(1f), fontSize = 11.sp, textAlign = TextAlign.Center) }
                }
                Text("진한 막대 = 확정/직접입력, 연한 막대 = 계산값", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f), modifier = Modifier.padding(top = 4.dp))
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (history.isEmpty()) Text("아직 기록이 없어요", fontSize = 14.sp)
            history.forEach { h ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        TextButton(onClick = { cid = h.companyId; setYm(h.ym) }, contentPadding = PaddingValues(0.dp)) {
                            Text("${h.ym.year}.${"%02d".format(h.ym.monthValue)}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text(h.companyName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
                    }
                    Tag(h.kind, if (h.kind == "확정" || h.kind == "직접입력") OkGreen else OtOrange)
                    Text(won(h.net), Modifier.padding(start = 10.dp), fontWeight = FontWeight.Bold)
                }
            }
            if (history.isNotEmpty()) {
                val year = YearMonth.now().year
                val yearSum = history.filter { it.ym.year == year }.sumOf { it.net }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                KV("${year}년 실수령 합계", won(yearSum), true)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ManualPayDialog(ym: YearMonth, c: Company, onDismiss: () -> Unit, onSave: (Int, Int) -> Unit) {
    var g by remember { mutableStateOf("") }
    var d by remember { mutableStateOf("") }
    val gi = g.filter { it.isDigit() }.toIntOrNull()
    val di = d.filter { it.isDigit() }.toIntOrNull()
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("${ym.year}년 ${ym.monthValue}월 실제 급여") },
        text = {
            Column {
                Text("${c.name} 명세서 금액을 그대로 입력하세요. 이 달 계산값 대신 이력에 쓰여요.", fontSize = 13.sp)
                Field("지급 합계(세전)", g, { g = it }, number = true)
                Field("공제 합계", d, { d = it }, number = true)
                if (gi != null && di != null) Text("실수령 ${won(gi - di)}", fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(gi!!, di!!) }, enabled = gi != null && di != null && di <= gi) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}
