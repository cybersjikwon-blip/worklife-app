package kr.worklife.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.worklife.bg.CardIngest
import kr.worklife.bg.Widgets
import kr.worklife.core.*
import kr.worklife.data.Repo
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

private fun wonL(v: Long) = "%,d원".format(v)
private val CancelRed = Color(0xFFE5484D)

/** 카드 탭: BC카드 승인 문자·알림톡 합산, 카드별(최대 5개 — 카드마다 문자 번호) 합계 */
@Composable
fun CardScreen(version: Int, bump: () -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val repo = Repo.get(ctx)
    var tick by remember { mutableIntStateOf(0) }
    val refresh: () -> Unit = { tick++; runCatching { Widgets.updateAll(ctx) } }
    val today = LocalDate.now()
    val period = remember(version, tick) { repo.cardPeriod }
    val people = remember(version, tick) { repo.cardPeople }
    val bcOnly = remember(version, tick) { repo.cardBcOnly }
    val (from, to) = cardRange(period, today)
    val txs = remember(version, tick, from, to) { repo.cardTxs(from, to) }
    val sm = remember(txs, people, bcOnly) { summarizeCards(txs, people, from, to, bcOnly) }

    var filter by remember { mutableIntStateOf(-2) } // -2 전체, -1 미지정, 0~4 카드
    var editTx by remember { mutableStateOf<CardTx?>(null) }
    var editPeople by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var assignCard by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var showN by remember { mutableIntStateOf(50) }

    var pasting by remember { mutableStateOf(false) }
    fun runImport(label: String, job: () -> Pair<Int, Int>) {
        if (busy) return
        busy = true; msg = "$label 읽는 중…"
        Thread {
            val r = runCatching { job() }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                busy = false
                msg = r.fold({ (found, added) ->
                    if (found == 0) "$label: 카드 승인 내역을 찾지 못했어요" else "$label: 결제 ${found}건 찾음 · 새로 ${added}건 추가" + (if (found > added) " (${found - added}건은 이미 있음)" else "")
                }, { "$label 읽기 실패: ${it.message ?: "알 수 없는 오류"}" })
                refresh()
            }
        }.start()
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runImport("파일") { CardIngest.importFile(ctx, uri) }
    }
    // 문자앱 [공유]로 들어온 글
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) runImport("캡처 ${uris.size}장") { kr.worklife.bg.CardOcr.importImages(ctx, uris) }
    }
    LaunchedEffect(version) {
        CardShare.take()?.let { t -> runImport("공유한 문자") { CardIngest.importText(ctx, t) } }
        CardShare.takeImages()?.let { l -> runImport("공유한 캡처 ${l.size}장") { kr.worklife.bg.CardOcr.importImages(ctx, l) } }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BigHeader("카드 사용액", cardRangeLabel(period, today))

        // ── 자동 합산: 알림 접근 (문자 권한 없이)
        if (!CardIngest.hasNotiAccess(ctx)) SCard(color = MaterialTheme.colorScheme.primaryContainer) {
            Text("자동으로 합산하려면 '알림 접근'을 켜 주세요", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text("BC카드 승인 문자·카카오 알림톡·페이북 알림이 올 때 카드 승인만 골라 합산해요. 다른 알림 내용은 저장하지 않고, 폰 밖으로 나가지 않아요.\n" +
                "열린 화면에서 '슬기로운 직장생활'을 찾아 켜면 돼요.", fontSize = 14.sp)
            Button(onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } },
                Modifier.padding(top = 10.dp)) { Text("알림 접근 켜기") }
        }

        // ── 기간: 언제부터 합산할지
        SCard {
            CardTitle("언제부터 합산할까요?")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("month" to "이번 달", "last" to "지난 달", "cycle" to "카드 이용기간", "custom" to "직접 지정").forEach { (k, l) ->
                    FilterChip(period.mode == k, {
                        repo.cardPeriod = if (k == "custom" && period.customStart == null) period.copy(mode = k, customStart = from) else period.copy(mode = k)
                        refresh()
                    }, label = { Text(l) })
                }
            }
            when (period.mode) {
                "cycle" -> Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("이용기간 시작일: 매월", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { repo.cardPeriod = period.copy(cycleStartDay = (period.cycleStartDay - 1).let { if (it < 1) 28 else it }); refresh() }) { Text("−") }
                    Text("${period.cycleStartDay}일", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp))
                    OutlinedButton(onClick = { repo.cardPeriod = period.copy(cycleStartDay = (period.cycleStartDay % 28) + 1); refresh() }) { Text("+") }
                }
                "custom" -> Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickDate(ctx, period.customStart ?: from) { d -> repo.cardPeriod = period.copy(customStart = d); refresh() } },
                        Modifier.weight(1f)) { Text("시작 ${(period.customStart ?: from).let { "${it.year % 100}.${it.monthValue}.${it.dayOfMonth}" }}") }
                    OutlinedButton(onClick = { pickDate(ctx, period.customEnd ?: today) { d -> repo.cardPeriod = period.copy(customEnd = d); refresh() } },
                        Modifier.weight(1f)) { Text(period.customEnd?.let { "끝 ${it.year % 100}.${it.monthValue}.${it.dayOfMonth}" } ?: "끝 오늘까지") }
                }
            }
            if (period.mode == "custom" && period.customEnd != null)
                TextButton(onClick = { repo.cardPeriod = period.copy(customEnd = null); refresh() }, contentPadding = PaddingValues(0.dp)) { Text("끝을 '오늘까지'로", fontSize = 13.sp) }
            Text("카드 이용기간은 카드 명세서의 '이용기간' 시작일(예: 매월 1일, 14일)을 넣으면 결제될 금액과 맞춰져요. 앱 설치 전 문자도 문자함에 남아 있으면 아래 [문자함에서 불러오기]로 채워져요.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f), modifier = Modifier.padding(top = 8.dp))
        }

        // ── 총합
        SCard(color = MaterialTheme.colorScheme.primaryContainer) {
            Text("지금까지 사용", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
            Text(wonL(sm.total), fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("${sm.count}건" + (if (sm.cancelTotal != 0L) " · 취소 ${wonL(-sm.cancelTotal)} 차감" else ""), fontSize = 14.sp)
            if (sm.foreignPending > 0) Text("해외 결제 ${sm.foreignPending}건은 원화 금액이 정해지지 않아 0원으로 들어가 있어요. 내역에서 눌러 금액을 넣어주세요.",
                fontSize = 12.sp, color = OtOrange, modifier = Modifier.padding(top = 6.dp))
        }

        // ── 사람별
        SCard {
            CardTitle("카드별 합계 (최대 ${CardPerson.MAX}개)", trailing = { TextButton(onClick = { editPeople = true }) { Text("카드 등록") } })
            if (people.isEmpty()) Text("[카드 등록]에서 카드마다 이름·문자 전화번호·끝자리를 넣으면 카드별로 나눠 합산해요. 5개 카드의 승인 문자가 이 폰으로 오게(또는 전달되게) 해 주세요.",
                fontSize = 14.sp)
            val totalAbs = sm.total.coerceAtLeast(1)
            people.forEachIndexed { i, p ->
                val (amt, n) = sm.perPerson.getOrElse(i) { 0L to 0 }
                PersonRow(p.name, listOfNotNull(p.phone.ifBlank { null }, p.cards.takeIf { it.isNotEmpty() }?.joinToString(", ", "카드 ")).joinToString(" · "),
                    amt, n, amt.toFloat() / totalAbs, filter == i) { filter = if (filter == i) -2 else i }
            }
            if (sm.unassigned.second > 0 || sm.unassigned.first != 0L)
                PersonRow("미지정", "어느 카드인지 아직 몰라요 — 내역을 눌러 지정", sm.unassigned.first, sm.unassigned.second,
                    sm.unassigned.first.toFloat() / totalAbs, filter == -1) { filter = if (filter == -1) -2 else -1 }
        }

        // ── 합산 안 한 다른 카드
        if (sm.otherCards.isNotEmpty()) SCard {
            CardTitle("합산하지 않은 다른 카드 문자")
            sm.otherCards.forEach { (card, amt) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("끝자리 $card", Modifier.weight(1f), fontSize = 14.sp)
                    Text(wonL(amt), fontSize = 14.sp)
                    TextButton(onClick = { assignCard = card }) { Text("포함") }
                }
            }
            Text("'BC' 표시가 없는 은행계 BC카드(예: 기업·농협 BC)면 [포함]을 눌러 등록한 카드에 연결하세요. 다음부터 자동으로 합산돼요.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
        }

        // ── 내역
        SCard {
            val title = when (filter) { -2 -> "사용 내역"; -1 -> "미지정 내역"; else -> "${people.getOrNull(filter)?.name ?: ""} 내역" }
            CardTitle(title, trailing = { if (filter != -2) TextButton(onClick = { filter = -2 }) { Text("전체 보기") } })
            val shown = txs.filter { t ->
                when (filter) {
                    -2 -> true
                    else -> cardIncluded(t, people, bcOnly) && matchPerson(t, people) == filter
                }
            }
            if (shown.isEmpty()) Text(if (CardIngest.hasNotiAccess(ctx)) "이 기간 승인 내역이 없어요." else "알림 접근을 켜거나 아래에서 지난 내역을 불러오면 채워져요.", fontSize = 14.sp)
            shown.take(showN).forEach { t ->
                val inc = cardIncluded(t, people, bcOnly)
                val who = matchPerson(t, people).let { if (it >= 0) people[it].name else if (inc) "미지정" else "합산 제외" }
                Row(Modifier.fillMaxWidth().clickable { editTx = t }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.merchant.ifBlank { "(가맹점 정보 없음)" }, fontSize = 15.sp, maxLines = 1,
                            color = if (inc) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .4f))
                        Text(listOfNotNull("${t.ts.monthValue}/${t.ts.dayOfMonth} ${t.ts.toLocalTime().hhmm()}", who,
                            t.card.ifBlank { null }, if (t.installment > 0) "${t.installment}개월" else null,
                            t.foreign.ifBlank { null }, if (t.source == "noti") "알림톡" else if (t.source == "manual") "직접" else null,
                            if (t.excluded) "제외됨" else null).joinToString(" · "),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f), maxLines = 1)
                    }
                    Text((if (t.isCancel) "−" else "") + wonL(kotlin.math.abs(t.amount)), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = when { !inc -> MaterialTheme.colorScheme.onSurface.copy(alpha = .4f); t.isCancel -> CancelRed; else -> MaterialTheme.colorScheme.onSurface })
                }
            }
            if (shown.size > showN) TextButton(onClick = { showN += 100 }) { Text("더 보기 (${shown.size - showN}건)") }
        }

        // ── 불러오기·설정
        SCard {
            CardTitle("불러오기 · 설정")
            Text("지난 내역 채우기 (알림 접근을 켜기 전 결제)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
            Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    runCatching { imageLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                }, enabled = !busy) { Text("캡처 불러오기") }
                OutlinedButton(onClick = { pasting = true }, enabled = !busy) { Text("문자 붙여넣기") }
                OutlinedButton(onClick = {
                    runCatching { fileLauncher.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.ms-excel", "text/csv", "text/comma-separated-values", "text/plain", "text/html", "*/*")) }
                }, enabled = !busy) { Text("이용내역 파일") }
                OutlinedButton(onClick = { adding = true }) { Text("직접 추가") }
            }
            Text("· 카드 모양 승인 문자(RCS)처럼 공유가 안 되면: 문자 화면을 캡처 → [캡처 불러오기] (여러 장 가능, 한 장에 여러 건도 OK). 갤러리에서 캡처를 [공유] → '카드 사용액에 추가'도 돼요\n" +
                "· 일반 문자는 길게 눌러 [공유] → '카드 사용액에 추가'\n" +
                "· BC카드 홈페이지/페이북의 이용내역을 엑셀(xlsx·xls)·CSV로 받아 [이용내역 파일]로 불러오면 한 번에 채워져요\n" +
                "· 이미 있는 결제는 다시 넣어도 1건으로 셉니다",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f), modifier = Modifier.padding(top = 6.dp))
            msg?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
            SwitchRow("BC카드만 합산", bcOnly) { repo.cardBcOnly = it; refresh() }
            var kw by remember(version) { mutableStateOf(repo.cardKeywords.joinToString(", ")) }
            Field("BC 구분 단어 (쉼표로)", kw, { kw = it; repo.cardKeywords = it.split(",").map { s -> s.trim() }.filter { s -> s.isNotEmpty() }; tick++ },
                hint = "문자에 이 단어가 있으면 BC카드로 봐요. 등록한 카드 끝자리·번호는 항상 합산")
            Text("큰 위젯 맨 아랫줄에 이 기간 사용액을 보여줄 수 있어요 (설정 > 위젯).", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
        }
        Spacer(Modifier.height(24.dp))
    }

    if (editPeople) PeopleDialog(people, onDismiss = { editPeople = false }) { repo.cardPeople = it; editPeople = false; refresh() }
    editTx?.let { t -> TxDialog(t, people, onDismiss = { editTx = null },
        onSave = { nt, alwaysCard ->
            repo.updateCardTx(nt)
            if (alwaysCard && nt.person >= 0 && nt.card.isNotBlank()) {
                repo.cardPeople = people.mapIndexed { i, p -> if (i == nt.person && p.cards.none { cardEq(it, nt.card) }) p.copy(cards = p.cards + nt.card) else p }
                repo.updateCardTx(nt.copy(person = -1)) // 카드로 자동 매칭되게
            }
            editTx = null; refresh()
        },
        onDelete = { repo.deleteCardTx(t.id); editTx = null; refresh() }) }
    if (pasting) PasteDialog(onDismiss = { pasting = false }) { t -> pasting = false; runImport("붙여넣은 문자") { CardIngest.importText(ctx, t) } }
    if (adding) AddTxDialog(people, onDismiss = { adding = false }) { repo.addCardTx(it); adding = false; refresh() }
    assignCard?.let { card -> AssignCardDialog(card, people, onDismiss = { assignCard = null }) { i, newName ->
        val list = if (i < 0) people + CardPerson(newName.ifBlank { "카드 ${people.size + 1}" }, cards = listOf(card))
            else people.mapIndexed { j, p -> if (j == i) p.copy(cards = p.cards + card) else p }
        repo.cardPeople = list; assignCard = null; refresh()
    } }
}

@Composable
private fun PersonRow(name: String, sub: String, amount: Long, count: Int, ratio: Float, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f), maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(wonL(amount), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("${count}건", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
            }
        }
        LinearProgressIndicator(progress = { ratio.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

/** 카드 등록: 최대 5개. 카드 이름 / 문자 전화번호 / 카드 끝자리(쉼표) / 문자 속 이름(예: 홍*동) — 하나라도 맞으면 그 카드 */
@Composable
private fun PeopleDialog(init: List<CardPerson>, onDismiss: () -> Unit, onSave: (List<CardPerson>) -> Unit) {
    val rows = remember { mutableStateListOf<Array<String>>().apply {
        init.forEach { add(arrayOf(it.name, it.phone, it.cards.joinToString(", "), it.nameHint)) }
        if (isEmpty()) add(arrayOf("카드 1", "", "", ""))
    } }
    var ver by remember { mutableIntStateOf(0) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("카드 등록 (최대 ${CardPerson.MAX}개)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                key(ver) {
                    rows.forEachIndexed { i, r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { rows.removeAt(i); ver++ }) { Text("삭제") }
                        }
                        var a by remember { mutableStateOf(r[0]) }; var b by remember { mutableStateOf(r[1]) }
                        var c by remember { mutableStateOf(r[2]) }; var d by remember { mutableStateOf(r[3]) }
                        Field("카드 이름", a, { a = it; r[0] = it }, hint = "예: 내 BC, 아내 BC, 법인 BC")
                        Field("문자 전화번호", b, { b = it; r[1] = it }, number = true, hint = "이 카드 승인 문자를 받는(전달해 주는) 폰 번호")
                        Field("카드 끝자리", c, { c = it; r[2] = it }, hint = "문자 속 (1234) 또는 1*2* 그대로 · 쉼표로 여러 개")
                        Field("문자 속 이름 (선택)", d, { d = it; r[3] = it }, hint = "예: 홍*동 — 끝자리·번호가 없을 때만 사용")
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    }
                }
                if (rows.size < CardPerson.MAX) TextButton(onClick = { rows.add(arrayOf("카드 ${rows.size + 1}", "", "", "")); ver++ }) { Text("+ 카드 추가") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(rows.filter { it[0].isNotBlank() }.map { r ->
                    CardPerson(r[0].trim(), r[1].trim(), r[2].split(",", " ").map { it.trim() }.filter { it.isNotEmpty() }, r[3].trim())
                })
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}

/** 내역 수정: 금액(해외 결제 원화), 가맹점, 사람 지정, 합산 제외, 삭제 */
@Composable
private fun TxDialog(t: CardTx, people: List<CardPerson>, onDismiss: () -> Unit, onSave: (CardTx, Boolean) -> Unit, onDelete: () -> Unit) {
    var amt by remember { mutableStateOf(kotlin.math.abs(t.amount).toString()) }
    var merchant by remember { mutableStateOf(t.merchant) }
    var person by remember { mutableIntStateOf(t.person) }
    var excluded by remember { mutableStateOf(t.excluded) }
    var always by remember { mutableStateOf(t.card.isNotBlank()) }
    var confirmDel by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("${t.ts.monthValue}/${t.ts.dayOfMonth} ${t.ts.toLocalTime().hhmm()} " + if (t.isCancel) "취소" else "승인") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Field(if (t.foreign.isNotBlank()) "원화 금액 (${t.foreign})" else "금액(원)", amt, { amt = it.filter { c -> c.isDigit() } }, number = true)
                Field("가맹점", merchant, { merchant = it })
                Text("어느 카드?", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(person == -1, { person = -1 }, label = { Text("자동") })
                    people.forEachIndexed { i, p -> FilterChip(person == i, { person = i }, label = { Text(p.name) }) }
                }
                if (person >= 0 && t.card.isNotBlank()) SwitchRow("끝자리 ${t.card}는 항상 이 카드로", always) { always = it }
                SwitchRow("합산에서 제외", excluded) { excluded = it }
                if (t.raw.isNotBlank()) Text(t.raw, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f),
                    modifier = Modifier.padding(top = 8.dp))
                TextButton(onClick = { if (confirmDel) onDelete() else confirmDel = true }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (confirmDel) "한 번 더 누르면 삭제돼요" else "이 내역 삭제", color = CancelRed)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = amt.toLongOrNull() ?: kotlin.math.abs(t.amount)
                onSave(t.copy(amount = if (t.isCancel) -v else v, merchant = merchant.trim(), person = person, excluded = excluded), always && person >= 0)
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } })
}

/** 직접 추가 (문자가 안 온 결제, 현금처럼 넣고 싶은 것) */
@Composable
private fun AddTxDialog(people: List<CardPerson>, onDismiss: () -> Unit, onAdd: (CardTx) -> Unit) {
    val ctx = LocalContext.current
    var date by remember { mutableStateOf(LocalDate.now()) }
    var time by remember { mutableStateOf(LocalTime.now().withSecond(0).withNano(0)) }
    var amt by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    var person by remember { mutableIntStateOf(if (people.isNotEmpty()) 0 else -1) }
    var cancel by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("직접 추가") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickDate(ctx, date) { date = it } }, Modifier.weight(1f)) { Text("${date.monthValue}/${date.dayOfMonth}") }
                    OutlinedButton(onClick = { pickTime(ctx, time) { time = it } }, Modifier.weight(1f)) { Text(time.hhmm()) }
                }
                Field("금액(원)", amt, { amt = it.filter { c -> c.isDigit() } }, number = true)
                Field("가맹점·메모", merchant, { merchant = it })
                if (people.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    people.forEachIndexed { i, p -> FilterChip(person == i, { person = i }, label = { Text(p.name) }) }
                }
                SwitchRow("취소(환불)로 넣기", cancel) { cancel = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = amt.toLongOrNull() ?: return@TextButton
                onAdd(CardTx(ts = LocalDateTime.of(date, time), amount = if (cancel) -v else v, merchant = merchant.trim(),
                    isBc = true, source = "manual", raw = "직접 추가 ${System.currentTimeMillis()}", person = person))
            }, enabled = (amt.toLongOrNull() ?: 0) > 0) { Text("추가") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}

/** 다른 카드 끝자리를 등록 카드에 연결 */
@Composable
private fun AssignCardDialog(card: String, people: List<CardPerson>, onDismiss: () -> Unit, onPick: (Int, String) -> Unit) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("끝자리 $card 를 어느 카드로 합산할까요?") },
        text = {
            Column {
                people.forEachIndexed { i, p -> TextButton(onClick = { onPick(i, "") }) { Text(p.name) } }
                if (people.size < CardPerson.MAX) {
                    Field("새 카드 이름", newName, { newName = it })
                    TextButton(onClick = { onPick(-1, newName) }) { Text("새 카드로 등록") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } })
}

/** 공유로 들어온 글을 카드 탭에 넘기는 통로 */
object CardShare {
    @Volatile private var pending: String? = null
    @Volatile private var images: List<android.net.Uri>? = null
    fun put(t: String) { pending = t }
    fun take(): String? = pending.also { pending = null }
    fun putImages(l: List<android.net.Uri>) { images = l }
    fun takeImages(): List<android.net.Uri>? = images.also { images = null }
}

/** 승인 문자 여러 개를 한꺼번에 붙여넣기 */
@Composable
private fun PasteDialog(onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var t by remember { mutableStateOf("") }
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("승인 문자 붙여넣기") },
        text = {
            Column {
                Text("문자앱에서 승인 문자를 복사해 붙여넣으세요. 여러 개를 한꺼번에 넣어도 하나씩 나눠서 읽어요.", fontSize = 13.sp)
                TextButton(onClick = { clip.getText()?.text?.let { t = if (t.isBlank()) it else t + "\n\n" + it } }, contentPadding = PaddingValues(0.dp)) { Text("클립보드에서 붙여넣기") }
                OutlinedTextField(t, { t = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 280.dp), placeholder = { Text("[Web발신]\nBC카드(1234)승인\n…") })
            }
        },
        confirmButton = { TextButton(onClick = { onOk(t) }, enabled = t.isNotBlank()) { Text("가져오기") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}
