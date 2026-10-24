package kr.worklife.ui

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.worklife.core.*
import kr.worklife.data.Repo
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

private val WheelColors = listOf(
    Color(0xFF3E91FF), Color(0xFFFF8A3D), Color(0xFF0FA36B), Color(0xFFE5484D),
    Color(0xFF8E6CEF), Color(0xFF00A3BF), Color(0xFFF5B400), Color(0xFFEC5BA6),
)

fun share(ctx: Context, text: String) {
    runCatching {
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "공유")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
fun FunScreen(modifier: Modifier, initial: Int = 0, onBack: (() -> Unit)? = null) {
    var sub by rememberSaveable(initial) { mutableIntStateOf(initial) }
    Column(modifier.fillMaxSize()) {
        if (onBack != null) MiniAppHeader("재미", "🎡", onBack)
        else BigHeader("재미", "점심 메뉴도, 커피 내기도 공정하게")
        TabRow(selectedTabIndex = sub, containerColor = MaterialTheme.colorScheme.background) {
            listOf("점심 룰렛", "돌림판", "사다리").forEachIndexed { i, t ->
                Tab(selected = sub == i, onClick = { sub = i }, text = { Text(t) })
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when (sub) {
                0 -> LunchRoulette()
                1 -> SpinWheel()
                else -> Ladder()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ─────────────────────────── 점심 메뉴 룰렛 (슬롯머신) ───────────────────────────
@Composable
private fun LunchRoulette() {
    val ctx = LocalContext.current
    val repo = Repo.get(ctx)
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var menus by remember { mutableStateOf(repo.lunchMenus) }
    var avoid by remember { mutableStateOf(true) }
    var shown by remember { mutableStateOf("?") }
    var result by remember { mutableStateOf<String?>(null) }
    var spinning by remember { mutableStateOf(false) }
    var newMenu by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(repo.lunchHistory) }

    SCard {
        Box(Modifier.fillMaxWidth().height(150.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center) {
            Text(shown, fontSize = if (shown.length > 6) 30.sp else 40.sp, fontWeight = FontWeight.Bold,
                color = if (result != null && !spinning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center)
        }
        Button(onClick = {
            if (spinning || menus.isEmpty()) return@Button
            spinning = true; result = null
            scope.launch {
                val recent = if (avoid) repo.recentLunch(3) else emptySet()
                val pick = pickAvoidingRecent(menus, recent) ?: return@launch
                for (i in 0 until 26) {           // 점점 느려지며 멈춤
                    shown = menus[Random.nextInt(menus.size)]
                    delay((35 + i * i * 0.7).toLong())
                }
                shown = pick; result = pick; spinning = false
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                repo.lunchHistory = repo.lunchHistory + "${LocalDate.now()}|$pick"
                history = repo.lunchHistory
            }
        }, Modifier.fillMaxWidth().padding(top = 12.dp).height(52.dp), enabled = !spinning && menus.isNotEmpty()) {
            Text(if (spinning) "돌아가는 중…" else if (result == null) "룰렛 돌리기" else "다시 돌리기", fontSize = 17.sp)
        }
        result?.let { r ->
            TextButton(onClick = { share(ctx, "오늘 점심은 [$r] 어때요? (슬기로운 직장생활 룰렛 결과)") }) { Text("결과 공유하기") }
        }
        SwitchRow("최근 3일 동안 나온 메뉴는 빼기", avoid) { avoid = it }
    }

    SCard {
        CardTitle("메뉴 ${menus.size}개", trailing = { TextButton(onClick = { editing = !editing }) { Text(if (editing) "완료" else "편집") } })
        if (editing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field("메뉴 추가", newMenu, { newMenu = it }, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val m = newMenu.trim()
                    if (m.isNotEmpty() && m !in menus) { menus = menus + m; repo.lunchMenus = menus }
                    newMenu = ""
                }) { Text("추가") }
            }
            TextButton(onClick = { menus = Repo.DEFAULT_MENUS; repo.lunchMenus = menus }) { Text("기본 메뉴로 되돌리기") }
        }
        FlowRowCompat(menus) { m ->
            AssistChip(onClick = { if (editing && menus.size > 1) { menus = menus - m; repo.lunchMenus = menus } },
                label = { Text(if (editing) "$m ✕" else m) })
        }
    }

    if (history.isNotEmpty()) SCard {
        CardTitle("최근 결과")
        history.takeLast(7).reversed().forEach { e ->
            val (d, m) = e.split("|", limit = 2).let { if (it.size == 2) it[0] to it[1] else "" to e }
            KV(d, m)
        }
    }
}

/** 칩 여러 개를 줄바꿈하며 배치 (FlowRow 대신 간단 구현) */
@Composable
private fun <T> FlowRowCompat(items: List<T>, perRow: Int = 4, item: @Composable (T) -> Unit) {
    Column {
        items.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { item(it) } }
        }
    }
}

// ─────────────────────────── 돌림판 ───────────────────────────
@Composable
private fun SpinWheel() {
    val ctx = LocalContext.current
    val repo = Repo.get(ctx)
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var wheels by remember { mutableStateOf(repo.wheels) }
    var name by remember { mutableStateOf(wheels.keys.first()) }
    var itemsText by remember { mutableStateOf(wheels[name]!!.joinToString(", ")) }
    val items = itemsText.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }.take(12)
    val rot = remember { Animatable(0f) }
    var result by remember { mutableStateOf<String?>(null) }
    var spinning by remember { mutableStateOf(false) }
    val textColor = MaterialTheme.colorScheme.onSurface

    SCard {
        if (wheels.size > 1) FlowRowCompat(wheels.keys.toList(), 3) { k ->
            FilterChip(k == name, { name = k; itemsText = wheels[k]!!.joinToString(", "); result = null }, label = { Text(k) })
        }
        Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val n = items.size.coerceAtLeast(1)
                val sweep = 360f / n
                val r = size.minDimension / 2
                val c = Offset(size.width / 2, size.height / 2)
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true; color = android.graphics.Color.WHITE
                    textSize = (r * 0.13f).coerceIn(28f, 56f); textAlign = android.graphics.Paint.Align.CENTER
                    isFakeBoldText = true
                }
                rotate(rot.value, c) {
                    for (i in 0 until n) {
                        drawArc(WheelColors[i % WheelColors.size], -90f + i * sweep, sweep, true,
                            topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2))
                        val mid = Math.toRadians((-90f + i * sweep + sweep / 2).toDouble())
                        val tx = c.x + (r * 0.62f * cos(mid)).toFloat()
                        val ty = c.y + (r * 0.62f * sin(mid)).toFloat() + paint.textSize / 3
                        val label = items.getOrNull(i)?.let { if (it.length > 6) it.take(6) + "…" else it } ?: ""
                        drawContext.canvas.nativeCanvas.drawText(label, tx, ty, paint)
                    }
                }
                drawCircle(Color.White, r * 0.12f, c)
                // 위쪽 화살표
                val p = Path().apply { moveTo(c.x - r * 0.09f, c.y - r - 6f); lineTo(c.x + r * 0.09f, c.y - r - 6f); lineTo(c.x, c.y - r + r * 0.14f); close() }
                drawPath(p, textColor)
            }
        }
        result?.let { Text("결과: $it", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
        Button(onClick = {
            if (spinning || items.size < 2) return@Button
            spinning = true; result = null
            scope.launch {
                val target = rot.value + 360f * 6 + Random.nextFloat() * 360f
                rot.animateTo(target, tween(4200, easing = FastOutSlowInEasing))
                val norm = ((-rot.value % 360f) + 360f) % 360f
                val idx = floor(norm / (360f / items.size)).toInt().coerceIn(0, items.size - 1)
                result = items[idx]; spinning = false
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }, Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp), enabled = !spinning && items.size >= 2) {
            Text(if (spinning) "돌아가는 중…" else "돌리기", fontSize = 17.sp)
        }
        result?.let { r -> TextButton(onClick = { share(ctx, "[$name] 돌림판 결과: $r") }) { Text("결과 공유하기") } }
    }

    SCard {
        CardTitle("항목 (쉼표로 구분, 최대 12개)")
        OutlinedTextField(itemsText, { itemsText = it; result = null }, Modifier.fillMaxWidth(), minLines = 2)
        var saveName by remember(name) { mutableStateOf(name) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field("돌림판 이름", saveName, { saveName = it }, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                if (saveName.isNotBlank() && items.size >= 2) {
                    wheels = wheels + (saveName.trim() to items); repo.wheels = wheels; name = saveName.trim()
                }
            }) { Text("저장") }
        }
        if (wheels.size > 1) TextButton(onClick = {
            wheels = wheels - name; repo.wheels = wheels
            name = wheels.keys.first(); itemsText = wheels[name]!!.joinToString(", ")
        }) { Text("'$name' 삭제") }
    }
}

// ─────────────────────────── 사다리타기 ───────────────────────────
@Composable
private fun Ladder() {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var n by remember { mutableIntStateOf(4) }
    var names by remember { mutableStateOf(List(8) { "${it + 1}번" }) }
    var results by remember { mutableStateOf(List(8) { if (it == 0) "당첨" else "꽝" }) }
    var ladder by remember { mutableStateOf<List<List<Boolean>>?>(null) }
    var who by remember { mutableIntStateOf(-1) }
    val prog = remember { Animatable(0f) }
    var showAll by remember { mutableStateOf(false) }
    val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f)

    SCard {
        CardTitle("참가 인원")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (2..8).forEach { k -> FilterChip(n == k, { n = k; ladder = null; who = -1; showAll = false }, label = { Text("$k") }) }
        }
        Text("이름 / 결과", fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        for (i in 0 until n) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("참가자 ${i + 1}", names[i], { v -> names = names.toMutableList().also { it[i] = v }; ladder = null }, modifier = Modifier.weight(1f))
            Field("결과 ${i + 1}", results[i], { v -> results = results.toMutableList().also { it[i] = v }; ladder = null }, modifier = Modifier.weight(1f))
        }
        Button(onClick = { ladder = makeLadder(n); who = -1; showAll = false; scope.launch { prog.snapTo(0f) } },
            Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp)) { Text(if (ladder == null) "사다리 만들기" else "사다리 다시 섞기", fontSize = 17.sp) }
    }

    val lad = ladder ?: return
    SCard {
        // 이름 버튼 (누르면 그 사람 길을 따라 내려감)
        Row(Modifier.fillMaxWidth()) {
            for (i in 0 until n) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                FilledTonalButton(onClick = {
                    who = i; showAll = false
                    scope.launch {
                        prog.snapTo(0f); prog.animateTo(1f, tween(1800, easing = LinearEasing))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(names[i].take(4), fontSize = 12.sp, maxLines = 1) }
            }
        }
        val pathColor = if (who >= 0) WheelColors[who % WheelColors.size] else Color.Transparent
        Canvas(Modifier.fillMaxWidth().height(320.dp).padding(vertical = 6.dp)) {
            val cols = n
            val cw = size.width / cols
            val rows = lad.size
            val rh = size.height / (rows + 1)
            fun x(c: Int) = cw * (c + 0.5f)
            fun y(r: Int) = rh * (r + 1)
            for (c in 0 until cols) drawLine(lineColor, Offset(x(c), 0f), Offset(x(c), size.height), 5f, StrokeCap.Round)
            for ((r, row) in lad.withIndex()) for ((g, on) in row.withIndex())
                if (on) drawLine(lineColor, Offset(x(g), y(r)), Offset(x(g + 1), y(r)), 5f, StrokeCap.Round)
            if (who >= 0) {
                val (pts, _) = ladderPath(lad, who)
                val coords = pts.map { (r, c) -> Offset(x(c), if (r < 0) 0f else if (r >= rows) size.height else y(r)) }
                val segs = coords.zipWithNext()
                val lens = segs.map { (a, b) -> (b - a).getDistance() }
                var remain = lens.sum() * prog.value
                val path = Path().apply { moveTo(coords.first().x, coords.first().y) }
                for ((k, s) in segs.withIndex()) {
                    if (remain <= 0f) break
                    val f = (remain / lens[k].coerceAtLeast(0.01f)).coerceAtMost(1f)
                    val p = s.first + (s.second - s.first) * f
                    path.lineTo(p.x, p.y); remain -= lens[k]
                }
                drawPath(path, pathColor, style = Stroke(12f, cap = StrokeCap.Round))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            for (i in 0 until n) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(results[i].take(5), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            }
        }
        if (who >= 0 && prog.value >= 1f) {
            val end = ladderPath(lad, who).second
            Text("${names[who]} → ${results[end]}", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                color = WheelColors[who % WheelColors.size], modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center)
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showAll = !showAll }) { Text(if (showAll) "전체 결과 닫기" else "전체 결과") }
            TextButton(onClick = {
                share(ctx, "사다리타기 결과\n" + (0 until n).joinToString("\n") { "${names[it]} → ${results[ladderPath(lad, it).second]}" })
            }) { Text("공유") }
        }
        if (showAll) (0 until n).forEach { i -> KV(names[i], results[ladderPath(lad, i).second], bold = results[ladderPath(lad, i).second] != "꽝") }
    }
}
