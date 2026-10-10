package kr.worklife.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.worklife.R
import kr.worklife.bg.*
import java.time.YearMonth

class MainActivity : ComponentActivity() {
    /** 데이터가 바뀌면 +1 → 화면 전체 다시 읽기 */
    val version = mutableIntStateOf(0)
    /** 현재 탭 — 실행·재실행 시 항상 홈(0) */
    val tab = mutableIntStateOf(0)
    private val ready = mutableStateOf(false)
    private var stoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notif.init(this)
        tab.intValue = 0
        setContent {
            WorkTheme {
                if (!ready.value) SplashScreen()
                else AppRoot(version.intValue, tab.intValue, { tab.intValue = it }) { version.intValue++ }
            }
        }
        // 로딩 화면 약 1초: 그동안 실제 준비 작업(지난 날 정리·오늘 판정·예약)을 백그라운드에서 처리
        val t0 = System.currentTimeMillis()
        Thread {
            runCatching { prepare() }
            val wait = 1100 - (System.currentTimeMillis() - t0)
            if (wait > 0) Thread.sleep(wait)
            runOnUiThread { ready.value = true; afterReady() }
        }.start()
    }

    private fun prepare() {
        Engine.settlePast(this)
        Engine.checkToday(this)
        Scheduler.scheduleAll(this)
        Geo.register(this)
    }

    private fun afterReady() {
        runCatching { Scheduler.ensure(this) }
        runCatching { Updater.autoCheck(this, notify = false) { runOnUiThread { version.intValue++ } } }
        // 앱을 열면 감시 중인 시간이면 즉시 위치 1회 확인 → 홈에 바로 반영
        runCatching { Tracker.oneShot(this) { runOnUiThread { version.intValue++ } } }
        version.intValue++
    }

    override fun onResume() {
        super.onResume()
        if (ready.value) sync()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tab.intValue = 0 // 아이콘·알림으로 다시 열면 홈
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = System.currentTimeMillis()
    }

    override fun onRestart() {
        super.onRestart()
        // 1분 이상 다른 앱에 있다가 돌아오면 홈 (권한 설정 화면 잠깐 다녀온 건 유지)
        if (System.currentTimeMillis() - stoppedAt > 60_000) tab.intValue = 0
    }

    /** 앱이 보일 때마다: 지난 날 마감 → 오늘 판정 → 알람·지오펜스 재등록 → 필요하면 감지 시작 */
    fun sync() {
        runCatching { prepare() }
        afterReady()
    }
}

@Composable
fun SplashScreen() {
    var go by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { go = true }
    val scale by animateFloatAsState(if (go) 1f else 0.7f, tween(600, easing = FastOutSlowInEasing), label = "s")
    val fade by animateFloatAsState(if (go) 1f else 0f, tween(500), label = "a")
    val prog by animateFloatAsState(if (go) 1f else 0f, tween(1000, easing = FastOutSlowInEasing), label = "p")
    Box(Modifier.fillMaxSize().background(Color(0xFF3E91FF))) {
        Column(Modifier.align(Alignment.Center).alpha(fade), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.ic_launcher_fg), contentDescription = null, modifier = Modifier.size(160.dp).scale(scale))
            Text("슬기로운 직장생활", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("오늘도 수고 많으셨습니다", color = Color.White.copy(alpha = .8f), fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp).width(180.dp).height(6.dp)
            .clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .25f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(prog).clip(RoundedCornerShape(3.dp)).background(Color.White))
        }
    }
}

@Composable
fun AppRoot(version: Int, tab: Int, setTab: (Int) -> Unit, bump: () -> Unit) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    val tabs = listOf("홈" to Icons.Filled.Home, "근무기록" to Icons.Filled.DateRange,
        "급여" to Icons.Filled.List, "설정" to Icons.Filled.Settings)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(selected = tab == i, onClick = { setTab(i) },
                        icon = { Icon(icon, label) }, label = { Text(label) })
                }
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        when (tab) {
            0 -> HomeScreen(version, bump, m, goSettings = { setTab(3) }, goRecords = { setTab(1) })
            1 -> RecordsScreen(version, bump, month, { month = it }, m)
            2 -> PayScreen(version, bump, month, { month = it }, m)
            else -> SettingsScreen(version, bump, m)
        }
    }
    UpdateDialogHost(bump)

    // 뒤로가기: 다른 탭이면 홈으로, 홈이면 종료 확인
    var askExit by remember { mutableStateOf(false) }
    val act = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    BackHandler(enabled = true) { if (tab != 0) setTab(0) else askExit = true }
    if (askExit) AlertDialog(
        onDismissRequest = { askExit = false },
        title = { Text("앱을 종료할까요?") },
        text = { Text("앱을 닫아도 퇴근 감지와 알림은 계속 동작해요.") },
        confirmButton = { TextButton(onClick = { askExit = false; act?.finish() }) { Text("종료") } },
        dismissButton = { TextButton(onClick = { askExit = false }) { Text("취소") } },
    )
}
