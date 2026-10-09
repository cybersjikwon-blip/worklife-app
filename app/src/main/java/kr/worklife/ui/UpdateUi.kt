package kr.worklife.ui

import android.app.Activity
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.worklife.bg.Updater
import java.io.File

/** 업데이트 진행 상태 (탭을 옮겨도 유지되도록 화면 밖에 둠) */
object UpdateState {
    // 0 대기 · 1 다운로드 · 2 파일 검사 · 3 설치 준비 완료 · 4 설치 화면 표시
    var phase by mutableIntStateOf(0)
    var open by mutableStateOf(false)
    var release by mutableStateOf<Updater.Release?>(null)
    var done by mutableLongStateOf(0L)
    var total by mutableLongStateOf(0L)
    var startedAt by mutableLongStateOf(0L)
    var error by mutableStateOf<String?>(null)
    var needPermission by mutableStateOf(false)
    var apk: File? = null
    private var running = false

    fun show(r: Updater.Release) {
        if (release?.code != r.code) { phase = 0; apk = null; error = null; done = 0; total = 0 }
        release = r; open = true
    }

    fun start(ctx: Context) {
        val r = release ?: return
        val act = ctx as? Activity
        fun ui(f: () -> Unit) { if (act != null) act.runOnUiThread(f) else f() }
        val ready = apk
        if (ready != null && ready.exists()) { install(ctx, ready); return }
        if (running) return
        running = true; error = null; needPermission = false
        phase = 1; done = 0; total = r.size; startedAt = System.currentTimeMillis()
        Thread {
            val res = runCatching {
                Updater.download(ctx.applicationContext, r) { d, t ->
                    ui { done = d; if (t > 0) total = t; if (t > 0 && d >= t) phase = 2 }
                }
            }
            ui {
                running = false
                res.onSuccess { f -> apk = f; phase = 3; install(ctx, f) }
                    .onFailure { e -> error = e.message ?: "알 수 없는 오류"; if (phase >= 2) phase = 1 }
            }
        }.start()
    }

    private fun install(ctx: Context, f: File) {
        if (Updater.install(ctx, f)) { phase = 4; needPermission = false } else needPermission = true
    }
}

@Composable
fun UpdateDialogHost(bump: () -> Unit) {
    if (!UpdateState.open) return
    val ctx = LocalContext.current
    val st = UpdateState
    val r = st.release ?: return
    val failed = st.error != null
    val frac = if (st.total > 0) (st.done.toFloat() / st.total).coerceIn(0f, 1f) else 0f
    val anim by animateFloatAsState(if (st.phase >= 3) 1f else frac, tween(250), label = "upd")
    val barColor = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val busy = st.phase == 1 || st.phase == 2

    AlertDialog(
        onDismissRequest = { st.open = false },
        title = { Text("새 버전 ${r.name}", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("현재 ${Updater.currentName(ctx)} → ${r.name} · 기록은 그대로 유지돼요", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                if (r.notes.isNotBlank()) Text(r.notes.take(400), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))

                if (st.phase > 0 || failed) {
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(if (st.phase >= 3) "100" else "${(frac * 100).toInt()}", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = barColor)
                        Text("%", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = barColor, modifier = Modifier.padding(start = 2.dp, bottom = 5.dp))
                        Spacer(Modifier.weight(1f))
                        Text(statusText(st), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f),
                            modifier = Modifier.padding(bottom = 6.dp))
                    }
                    // 둥근 진행 막대
                    Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(anim).clip(RoundedCornerShape(6.dp)).background(barColor))
                    }
                    Text("${Updater.mb(st.done)} / ${Updater.mb(st.total)}" + eta(st), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), modifier = Modifier.padding(top = 6.dp))

                    Spacer(Modifier.height(14.dp))
                    val steps = listOf("다운로드", "파일 검사", "설치 준비", "설치")
                    steps.forEachIndexed { i, label ->
                        val stepNo = i + 1
                        val doneStep = st.phase > stepNo || (st.phase == 4 && stepNo == 4)
                        val active = st.phase == stepNo && !failed
                        StepRow(stepNo, label, doneStep, active, failed && st.phase == stepNo)
                    }
                }
                st.error?.let { Text("⚠ $it", color = MaterialTheme.colorScheme.error, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp)) }
                if (st.needPermission) Text("'이 출처의 앱 허용'을 켠 뒤 [설치]를 다시 눌러주세요.", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 8.dp))
                if (st.phase == 4) Text("설치 화면에서 [업데이트]를 누르면 끝나요.", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            when {
                failed -> Button(onClick = { st.start(ctx) }) { Text("다시 시도") }
                busy -> TextButton(onClick = { st.open = false }) { Text("백그라운드로") }
                st.phase >= 3 -> Button(onClick = { st.start(ctx) }) { Text("설치") }
                else -> Button(onClick = { st.start(ctx) }) { Text("지금 업데이트") }
            }
        },
        dismissButton = { if (!busy) TextButton(onClick = { st.open = false; bump() }) { Text("나중에") } },
    )
}

private fun statusText(st: UpdateState): String = when {
    st.error != null -> "실패"
    st.phase == 1 -> "받는 중"
    st.phase == 2 -> "검사 중"
    st.phase == 3 -> "준비 완료"
    st.phase == 4 -> "설치 중"
    else -> ""
}

private fun eta(st: UpdateState): String {
    if (st.phase != 1 || st.done <= 0 || st.total <= 0) return ""
    val sec = (System.currentTimeMillis() - st.startedAt) / 1000.0
    if (sec < 0.5) return ""
    val speed = st.done / sec
    val left = ((st.total - st.done) / speed).toInt()
    return " · 남은 시간 약 ${if (left >= 60) "${left / 60}분 ${left % 60}초" else "${left}초"}"
}

@Composable
private fun StepRow(no: Int, label: String, done: Boolean, active: Boolean, failed: Boolean) {
    val c = when { failed -> MaterialTheme.colorScheme.error; done || active -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.outline }
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).clip(CircleShape)
            .background(if (done) c else Color.Transparent).border(2.dp, c, CircleShape),
            contentAlignment = Alignment.Center) {
            if (done) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
            else Text("$no", fontSize = 12.sp, color = c, fontWeight = FontWeight.Bold)
        }
        Text(label, Modifier.padding(start = 10.dp), fontSize = 14.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (done || active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
        if (active) {
            Spacer(Modifier.width(10.dp))
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = c)
        }
    }
}
