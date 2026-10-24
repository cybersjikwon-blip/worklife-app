package kr.worklife.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kr.worklife.bg.LottoNet
import kr.worklife.bg.QrScan
import kr.worklife.core.*
import kr.worklife.data.Repo
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 로또 미니앱 — [번호 생성] [내 번호] [당첨·통계]
 *  - 다시 뽑기는 버튼 한 번 (앱을 나갔다 올 필요 없음)
 *  - 당첨번호는 한 번 받으면 폰에 저장, 화면을 막지 않고 백그라운드에서 받음
 *  - QR은 구글 스캐너(카메라 권한 불필요), 판매점은 지도 앱으로 연결(위치 권한 불필요)
 */
@Composable
fun LottoScreen(modifier: Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = Repo.get(ctx)
    var tab by remember { mutableIntStateOf(0) }
    var dataVer by remember { mutableIntStateOf(0) }
    var syncing by remember { mutableStateOf(false) }
    var syncMsg by remember { mutableStateOf<String?>(null) }
    var prog by remember { mutableStateOf(0 to 0) }
    val draws = remember(dataVer) { repo.draws() }
    val latest = draws.firstOrNull()
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    fun sync(want: Int) {
        if (syncing) return
        syncing = true; syncMsg = null; prog = 0 to 0
        Thread {
            val (ok, fail) = runCatching { LottoNet.sync(ctx, want) { d, n -> main.post { prog = d to n } } }.getOrDefault(0 to -1)
            main.post {
                syncing = false; dataVer++
                syncMsg = when {
                    fail == -1 -> "당첨번호를 받지 못했어요. 인터넷 연결을 확인해 주세요."
                    ok == 0 && fail == 0 -> null
                    fail > 0 && ok == 0 -> (LottoNet.lastError ?: "당첨번호를 받지 못했어요") + " · 위 카드를 눌러 다시 받기"
                    fail > 0 -> "${ok}회 받음 · ${fail}회는 아직 발표 전이거나 실패"
                    else -> "${ok}회 받음"
                }
            }
        }.start()
    }
    // 처음 열 때: 전 회차를 요청 1번에 (이미 있으면 받지 않음)
    LaunchedEffect(Unit) { sync(Lotto.latestRound()) }

    Column(modifier.fillMaxSize()) {
        MiniAppHeader("로또 6/45", "🎱", onBack) {
            TextButton(onClick = {
                // 근처 복권판매점: 지도 앱으로 (카카오맵 → 네이버지도 → 기본 지도 → 웹)
                val tries = listOf("kakaomap://search?q=복권판매점", "nmap://search?query=복권판매점&appname=kr.worklife",
                    "geo:0,0?q=복권판매점", "https://m.map.naver.com/search2/search.naver?query=복권판매점")
                tries.firstOrNull { u -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
            }) { Text("📍 판매점") }
        }
        // 이번 회차 + 지난 당첨번호
        val next = Lotto.nextRound()
        val drawDay = Lotto.dateOf(next)
        val dday = ChronoUnit.DAYS.between(java.time.LocalDate.now(), drawDay)
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF3E91FF), Color(0xFF2B3A8C))), RoundedCornerShape(24.dp))
            .clickable { sync(Lotto.latestRound()) }.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("제${next}회 · ${drawDay.monthValue}/${drawDay.dayOfMonth}(토) 추첨", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text(if (dday == 0L) "오늘 20:45" else "D-$dday", color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.background(Color.White.copy(alpha = .2f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp))
            }
            if (latest != null) {
                Text("제${latest.round}회 당첨번호 (${latest.date.monthValue}/${latest.date.dayOfMonth})", color = Color.White.copy(alpha = .75f), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    latest.nums.forEach { Ball(it, 30.dp); Spacer(Modifier.width(4.dp)) }
                    Text("+", color = Color.White, modifier = Modifier.padding(horizontal = 4.dp)); Ball(latest.bonus, 30.dp)
                }
                if (latest.firstWinners > 0) Text("1등 ${latest.firstWinners}명 · 각 ${"%,d".format(latest.firstPrize / 100_000_000)}억 ${"%,d".format(latest.firstPrize % 100_000_000 / 10_000)}만원",
                    color = Color.White.copy(alpha = .75f), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            } else Text(if (syncing) "당첨번호 받는 중…" else "당첨번호를 아직 받지 못했어요", color = Color.White.copy(alpha = .8f), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            if (syncing && prog.second > 1) LinearProgressIndicator(progress = { prog.first.toFloat() / prog.second }, Modifier.fillMaxWidth().padding(top = 8.dp),
                color = Color.White, trackColor = Color.White.copy(alpha = .25f))
            syncMsg?.let { Text(it, color = Color(0xFFFFE08A), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
        }
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            listOf("번호 생성", "내 번호", "당첨·통계").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when (tab) {
                0 -> LottoGenerate(latest) { tab = 1 }
                1 -> LottoMine(draws, dataVer) { sync(Lotto.latestRound()) }
                else -> LottoStats(draws, syncing, prog) { _ -> sync(Lotto.latestRound()) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 미니앱 공통 머리줄: ← 제목 [오른쪽 버튼] */
@Composable
fun MiniAppHeader(title: String, icon: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("←", fontSize = 22.sp) }
        Text("$icon $title", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun Ball(n: Int, size: Dp = 36.dp, dim: Boolean = false, ring: Boolean = false) {
    Box(Modifier.size(size).background(Color(Lotto.ballColor(n)).copy(alpha = if (dim) .25f else 1f), CircleShape)
        .then(if (ring) Modifier.border(2.dp, Color(0xFF111827), CircleShape) else Modifier), contentAlignment = Alignment.Center) {
        Text("$n", color = if (dim) Color(0xFF6B7280) else Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * .42f).sp)
    }
}

/** 번호 생성: 게임 수, 고정·제외(번호판 터치), 조건 4가지 — 결과는 공이 하나씩 튀어나옴 */
@Composable
private fun LottoGenerate(latest: LottoDraw?, goMine: () -> Unit) {
    val ctx = LocalContext.current
    val repo = Repo.get(ctx)
    val haptic = LocalHapticFeedback.current
    var opt by remember { mutableStateOf(repo.lottoOptions) }
    fun saveOpt(o: LottoOptions) { opt = o; repo.lottoOptions = o }
    var games by remember { mutableIntStateOf(5) }
    var result by remember { mutableStateOf<List<List<Int>>>(emptyList()) }
    var roll by remember { mutableIntStateOf(0) }
    var showOpt by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(setOf<Int>()) }
    var msg by remember { mutableStateOf<String?>(null) }

    SCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("게임 수", fontSize = 14.sp, modifier = Modifier.weight(1f))
            (1..5).forEach { n -> FilterChip(games == n, { games = n }, label = { Text("$n") }, modifier = Modifier.padding(start = 4.dp)) }
        }
        Button(onClick = {
            result = (0 until games).map { Lotto.generate(opt, latest) }
            roll++; saved = emptySet(); msg = null
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }, Modifier.fillMaxWidth().padding(top = 12.dp).height(52.dp), shape = RoundedCornerShape(16.dp)) {
            Text(if (result.isEmpty()) "🎱 번호 뽑기" else "🔄 다시 뽑기", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        val active = listOfNotNull(
            opt.fixed.takeIf { it.isNotEmpty() }?.let { "고정 ${it.sorted().joinToString(",")}" },
            opt.exclude.takeIf { it.isNotEmpty() }?.let { "제외 ${it.size}개" },
            if (opt.balanceOddEven) "홀짝 균형" else null, if (opt.sumRange) "합계 100~175" else null,
            if (opt.maxConsecutive > 0) "연속 ${opt.maxConsecutive}개까지" else null, if (opt.avoidLastDraw) "지난 번호 빼기" else null)
        TextButton(onClick = { showOpt = !showOpt }, contentPadding = PaddingValues(0.dp)) {
            Text((if (showOpt) "▲ 조건 접기" else "▼ 조건 설정") + (if (active.isEmpty()) " (완전 무작위)" else " · " + active.joinToString(" · ")), fontSize = 13.sp, maxLines = 1)
        }
        if (showOpt) {
            Text("번호를 눌러 고정(초록) → 제외(빨강) → 해제", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
            NumberPad(opt.fixed, opt.exclude) { n ->
                when (n) {
                    in opt.fixed -> saveOpt(opt.copy(fixed = opt.fixed - n, exclude = opt.exclude + n))
                    in opt.exclude -> saveOpt(opt.copy(exclude = opt.exclude - n))
                    else -> if (opt.fixed.size < 5) saveOpt(opt.copy(fixed = opt.fixed + n)) else saveOpt(opt.copy(exclude = opt.exclude + n))
                }
            }
            SwitchRow("홀짝 균형 (2:4 ~ 4:2)", opt.balanceOddEven) { saveOpt(opt.copy(balanceOddEven = it)) }
            SwitchRow("합계 100~175 (당첨의 약 70%)", opt.sumRange) { saveOpt(opt.copy(sumRange = it)) }
            SwitchRow("연속 번호 2개까지만", opt.maxConsecutive == 2) { saveOpt(opt.copy(maxConsecutive = if (it) 2 else 0)) }
            SwitchRow("지난 회차 당첨번호 빼기", opt.avoidLastDraw) { saveOpt(opt.copy(avoidLastDraw = it)) }
            if (active.isNotEmpty()) TextButton(onClick = { saveOpt(LottoOptions()) }) { Text("조건 모두 해제") }
        }
    }
    if (result.isNotEmpty()) SCard {
        result.forEachIndexed { gi, g ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("ABCDE"[gi].toString(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f), modifier = Modifier.width(18.dp))
                g.forEachIndexed { bi, n -> PopBall(n, roll, gi * 6 + bi); Spacer(Modifier.width(4.dp)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    repo.saveMyLotto(Lotto.nextRound(), listOf(g), "생성"); saved = saved + gi
                }, enabled = gi !in saved, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(if (gi in saved) "✓" else "저장") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            FilledTonalButton(onClick = {
                repo.saveMyLotto(Lotto.nextRound(), result, "생성"); saved = result.indices.toSet(); msg = "제${Lotto.nextRound()}회 내 번호에 저장했어요"
            }, enabled = saved.size < result.size) { Text("모두 저장") }
            OutlinedButton(onClick = {
                val text = "🎱 제${Lotto.nextRound()}회 추천 번호\n" + result.mapIndexed { i, g -> "${"ABCDE"[i]}  ${g.joinToString(" ") { "%2d".format(it) }}" }.joinToString("\n")
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "공유"))
            }) { Text("공유") }
        }
        msg?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(onClick = goMine).padding(top = 6.dp)) }
    }
}

/** 하나씩 튀어나오는 공 */
@Composable
private fun PopBall(n: Int, roll: Int, order: Int) {
    val sc = remember(roll, n) { Animatable(0f) }
    LaunchedEffect(roll, n) { delay(order * 35L); sc.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)) }
    Box(Modifier.scale(sc.value)) { Ball(n, 34.dp) }
}

@Composable
private fun NumberPad(fixed: Set<Int>, exclude: Set<Int>, onTap: (Int) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        (1..45).chunked(9).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { n ->
                    val bg = when (n) { in fixed -> Color(0xFF16A34A); in exclude -> Color(0xFFE5484D); else -> MaterialTheme.colorScheme.onSurface.copy(alpha = .06f) }
                    Box(Modifier.padding(2.dp).size(32.dp).background(bg, CircleShape).clickable { onTap(n) }, contentAlignment = Alignment.Center) {
                        Text("$n", fontSize = 13.sp, color = if (n in fixed || n in exclude) Color.White else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

/** 내 번호: 회차별, 추첨 후엔 등수와 맞은 번호 표시 / QR 스캔 / 직접 입력 */
@Composable
private fun LottoMine(draws: List<LottoDraw>, dataVer: Int, refresh: () -> Unit) {
    val ctx = LocalContext.current
    val repo = Repo.get(ctx)
    var ver by remember { mutableIntStateOf(0) }
    val mine = remember(ver, dataVer) { repo.myLotto() }
    val byRound = draws.associateBy { it.round }
    var msg by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf(false) }

    SCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                QrScan.scan(ctx, onText = { t ->
                    val q = Lotto.parseQr(t)
                    if (q == null) msg = "로또 용지 QR이 아니에요"
                    else { repo.saveMyLotto(q.first, q.second, "QR"); ver++; msg = "제${q.first}회 ${q.second.size}게임을 추가했어요"; if (q.first !in byRound) refresh() }
                }, onFail = { msg = it })
            }, Modifier.weight(1f)) { Text("📷 용지 QR 스캔") }
            OutlinedButton(onClick = { manual = true }, Modifier.weight(1f)) { Text("✏️ 직접 입력") }
        }
        msg?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
    }
    if (mine.isEmpty()) SCard { Text("저장한 번호가 없어요. [번호 생성]에서 저장하거나 산 용지의 QR을 스캔하세요.", fontSize = 14.sp) }
    mine.groupBy { it.round }.forEach { (round, list) ->
        val d = byRound[round]
        SCard(pad = 16.dp) {
            val ranks = d?.let { dd -> list.map { Lotto.rank(it.nums, dd) } }
            CardTitle("제${round}회 · ${Lotto.dateOf(round).monthValue}/${Lotto.dateOf(round).dayOfMonth}", trailing = {
                when {
                    ranks == null && round > Lotto.latestRound() -> Tag("추첨 전", MaterialTheme.colorScheme.primary)
                    ranks == null -> Tag("결과 받는 중", OtOrange)
                    ranks.any { it in 1..5 } -> Tag("🎉 " + ranks.filter { it > 0 }.min().let { Lotto.rankLabel(it) } + " 당첨!", OkGreen)
                    else -> Tag("낙첨", MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
                }
            })
            list.forEachIndexed { i, m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    m.nums.forEach { n -> Ball(n, 30.dp, dim = d != null && n !in d.nums && n != d.bonus, ring = d != null && n == d.bonus); Spacer(Modifier.width(3.dp)) }
                    Spacer(Modifier.weight(1f))
                    ranks?.get(i)?.let { r -> Text(Lotto.rankLabel(r), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (r > 0) OkGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = .45f)) }
                    TextButton(onClick = { repo.deleteMyLotto(m.id); ver++ }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("✕", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .4f)) }
                }
            }
            if (d != null) Text("당첨 ${d.nums.joinToString(" ")} + ${d.bonus}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
        }
    }
    if (manual) ManualLottoDialog(onDismiss = { manual = false }) { round, g -> repo.saveMyLotto(round, listOf(g), "직접"); ver++; manual = false }
}

@Composable
private fun ManualLottoDialog(onDismiss: () -> Unit, onAdd: (Int, List<Int>) -> Unit) {
    var round by remember { mutableStateOf(Lotto.nextRound().toString()) }
    var picked by remember { mutableStateOf(setOf<Int>()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("번호 직접 입력") },
        text = {
            Column {
                Field("회차", round, { round = it.filter { c -> c.isDigit() }.take(4) }, number = true)
                Text("번호 6개를 누르세요 (${picked.size}/6)", fontSize = 13.sp)
                NumberPad(picked, emptySet()) { n -> picked = if (n in picked) picked - n else if (picked.size < 6) picked + n else picked }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(round.toIntOrNull() ?: Lotto.nextRound(), picked.sorted()) }, enabled = picked.size == 6) { Text("추가") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}

/** 당첨·통계: 최근 50/100/300회, 많이·적게·오래 안 나온 번호, 번호별 막대 */
@Composable
private fun LottoStats(draws: List<LottoDraw>, syncing: Boolean, prog: Pair<Int, Int>, sync: (Int) -> Unit) {
    var n by remember { mutableIntStateOf(100) }
    val st = remember(draws, n) { Lotto.stats(draws, n) }
    val latest = Lotto.latestRound()
    SCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("최근", fontSize = 14.sp, modifier = Modifier.padding(end = 6.dp))
            listOf(50, 100, 300).forEach { k -> FilterChip(n == k, { n = k; if (draws.count { it.round > latest - k } < k) sync(k) }, label = { Text("${k}회") }, modifier = Modifier.padding(end = 4.dp)) }
        }
        Text("저장된 당첨번호 ${draws.size}회 · 최근 ${n}회 중 ${st.count}회로 계산" + (if (syncing) " · 받는 중 ${prog.first}/${prog.second}" else ""),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f), modifier = Modifier.padding(top = 4.dp))
        if (!syncing && st.count < n && st.count < latest) TextButton(onClick = { sync(n) }, contentPadding = PaddingValues(0.dp)) { Text("빠진 회차 받기") }
    }
    if (st.count == 0) { SCard { Text(if (syncing) "당첨번호를 받는 중이에요…" else "당첨번호가 없어요. 인터넷 연결 후 [빠진 회차 받기]를 눌러 주세요.") }; return }
    SCard {
        StatRow("🔥 많이 나온 번호", st.hot) { "${st.freq[it]}회" }
        StatRow("🧊 적게 나온 번호", st.cold) { "${st.freq[it]}회" }
        StatRow("⏳ 오래 안 나온 번호", st.longGap) { if (st.lastSeen[it] == 0) "-" else "${latest - st.lastSeen[it]}주" }
        Text("평균 홀수 ${"%.1f".format(st.oddAvg)}개 · 평균 합계 ${st.sumAvg.toInt()}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f), modifier = Modifier.padding(top = 8.dp))
    }
    SCard {
        CardTitle("번호별 출현 (최근 ${st.count}회)")
        val max = (1..45).maxOf { st.freq[it] }.coerceAtLeast(1)
        (1..45).forEach { k ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Ball(k, 22.dp)
                Box(Modifier.padding(horizontal = 8.dp).weight(1f).height(10.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .05f), RoundedCornerShape(5.dp))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(st.freq[k].toFloat() / max).background(Color(Lotto.ballColor(k)), RoundedCornerShape(5.dp)))
                }
                Text("${st.freq[k]}", fontSize = 12.sp, modifier = Modifier.width(26.dp))
            }
        }
        Text("※ 로또는 매번 독립적인 추첨이라 통계가 당첨 확률을 바꾸지는 않아요. 재미로 참고하세요.", fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f), modifier = Modifier.padding(top = 8.dp))
    }
    // 최근 당첨 결과 10회
    SCard {
        CardTitle("최근 당첨번호")
        draws.take(10).forEach { d ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${d.round}", fontSize = 12.sp, modifier = Modifier.width(40.dp))
                d.nums.forEach { Ball(it, 24.dp); Spacer(Modifier.width(2.dp)) }
                Text(" +", fontSize = 12.sp); Ball(d.bonus, 24.dp)
            }
        }
    }
}

@Composable
private fun StatRow(label: String, nums: List<Int>, sub: (Int) -> String) {
    Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        nums.forEach { k ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(end = 8.dp)) {
                Ball(k, 32.dp); Text(sub(k), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
            }
        }
    }
}
