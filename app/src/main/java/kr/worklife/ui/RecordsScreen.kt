package kr.worklife.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.worklife.bg.Engine
import kr.worklife.core.*
import kr.worklife.data.Repo
import java.time.LocalDate
import java.time.YearMonth

/** 이 달에 재직한 회사 선택(이직한 달이면 2개) */
@Composable
fun CompanyPicker(ym: YearMonth, selected: Long?, onSelect: (Company) -> Unit): Company? {
    val ctx = LocalContext.current
    val list = Repo.get(ctx).companiesIn(ym)
    val cur = list.firstOrNull { it.id == selected } ?: list.lastOrNull()
    if (list.size > 1) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        list.forEach { c -> FilterChip(selected = c.id == cur?.id, onClick = { onSelect(c) }, label = { Text(c.name) }) }
    }
    return cur
}

@Composable
fun RecordsScreen(version: Int, bump: () -> Unit, ym: YearMonth, setYm: (YearMonth) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val repo = Repo.get(ctx)
    var cid by remember { mutableStateOf<Long?>(null) }
    var editing by remember { mutableStateOf<DayRecord?>(null) }

    Column(modifier.fillMaxSize()) {
        BigHeader("근무기록")
        MonthNav(ym, setYm)
        val c = CompanyPicker(ym, cid) { cid = it.id }
        if (c == null) {
            Text("이 달에 재직한 회사가 없어요", Modifier.padding(24.dp)); return@Column
        }
        val today = LocalDate.now()
        val recs = remember(version, ym, c) { buildMonth(c, ym, repo.month(ym, c.id), today) }
        val tot = recs.sumOf { overtimeMin(c, it) }
        Text("근무 ${recs.count { it.status == Status.WORK && it.checkOut != null }}일 · 결근 ${recs.count { it.status == Status.ABSENT }} · " +
                "연차 ${recs.count { it.status == Status.LEAVE }} · 연장 ${hmShort(tot)}",
            Modifier.padding(horizontal = 24.dp, vertical = 8.dp), fontSize = 14.sp)
        LazyColumn(Modifier.fillMaxSize()) {
            items(recs, key = { it.day.toString() }) { r -> DayRow(c, r, today) { editing = r } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    editing?.let { r ->
        val c = repo.companies.first { it.id == r.companyId }
        EditDayDialog(c, r, onDismiss = { editing = null }, onSave = { repo.saveDay(it); editing = null; bump() },
            onReset = {
                repo.deleteDay(r.day, c.id)
                if (r.day == LocalDate.now()) Engine.checkToday(ctx) else Engine.settlePast(ctx)
                editing = null; bump()
            })
    }
}

@Composable
private fun DayRow(c: Company, r: DayRecord, today: LocalDate, onClick: () -> Unit) {
    val ot = overtimeMin(c, r)
    val faded = r.status == Status.HOLIDAY || r.day.isAfter(today)
    val alpha = if (faded) .45f else 1f
    SCard(Modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(58.dp)) {
                Text("${r.day.dayOfMonth}", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = (if (r.day.dayOfWeek.value == 7 || r.status == Status.HOLIDAY) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface).copy(alpha = alpha))
                Text(wd(r.day), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
            }
            Column(Modifier.weight(1f)) {
                val main = when (r.status) {
                    Status.HOLIDAY -> r.note.ifBlank { "휴일" }
                    Status.ABSENT, Status.LEAVE -> r.status.label
                    else -> "${r.checkIn?.hhmm() ?: "-"} ~ ${r.checkOut?.hhmm() ?: "예정"}"
                }
                Text(main, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                val sub = buildString {
                    if (r.status == Status.HOLIDAY_WORK) append("휴일근무 ")
                    if (r.status != Status.HOLIDAY && r.note.isNotBlank() && r.note != "예정") append(r.note)
                }
                if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f), maxLines = 2)
            }
            Column(horizontalAlignment = Alignment.End) {
                if (ot > 0) Text("+${hmShort(ot)}", color = OtOrange, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                when {
                    r.source == Source.NEEDS_CHECK -> Tag("확인필요", MaterialTheme.colorScheme.error)
                    r.status == Status.ABSENT -> Tag("결근", MaterialTheme.colorScheme.error)
                    r.status == Status.LEAVE -> Tag("연차", OkGreen)
                    r.status != Status.HOLIDAY && !r.day.isAfter(today) -> Tag(r.source.label, MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
fun EditDayDialog(c: Company, r: DayRecord, onDismiss: () -> Unit, onSave: (DayRecord) -> Unit, onReset: () -> Unit) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf(if (r.status == Status.HOLIDAY) Status.HOLIDAY_WORK else r.status) }
    var ci by remember { mutableStateOf(r.checkIn ?: hm(c.workStart)) }
    var co by remember { mutableStateOf(r.checkOut ?: hm(c.workEnd)) }
    var note by remember { mutableStateOf(if (r.note == "예정") "" else r.note) }
    val isHoliday = !HolidayCalendar(c).isWorkday(r.day)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${r.day.monthValue}월 ${r.day.dayOfMonth}일 (${wd(r.day)})") },
        text = {
            Column {
                val options = if (isHoliday) listOf(Status.HOLIDAY, Status.HOLIDAY_WORK) else listOf(Status.WORK, Status.ABSENT, Status.LEAVE)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.forEach { st -> FilterChip(status == st, { status = st }, label = { Text(st.label) }) }
                }
                if (status == Status.WORK || status == Status.HOLIDAY_WORK) {
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickTime(ctx, ci) { ci = it } }, Modifier.weight(1f)) { Text("출근 ${ci.hhmm()}") }
                        OutlinedButton(onClick = { pickTime(ctx, co) { co = it } }, Modifier.weight(1f)) { Text("퇴근 ${co.hhmm()}") }
                    }
                    val ot = overtimeMin(c, DayRecord(r.day, c.id, status, ci, co))
                    if (ot > 0) Text("연장 ${hmShort(ot)}", color = OtOrange, modifier = Modifier.padding(top = 6.dp))
                    if (co.isBefore(ci)) Text("퇴근이 출근보다 빨라요", color = MaterialTheme.colorScheme.error)
                }
                Field("메모", note, { note = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val keepTimes = status == Status.WORK || status == Status.HOLIDAY_WORK
                if (status == Status.HOLIDAY) onReset()
                else onSave(DayRecord(r.day, c.id, status, if (keepTimes) ci else null, if (keepTimes) co else null, Source.MANUAL, note))
            }, enabled = !(co.isBefore(ci) && (status == Status.WORK || status == Status.HOLIDAY_WORK))) { Text("저장") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text("자동값으로") }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        },
    )
}

