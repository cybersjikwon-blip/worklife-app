package kr.worklife.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import kr.worklife.bg.*
import java.time.YearMonth

class MainActivity : ComponentActivity() {
    /** 데이터가 바뀌면 +1 → 화면 전체 다시 읽기 */
    val version = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notif.init(this)
        setContent { WorkTheme { AppRoot(version.intValue) { version.intValue++ } } }
    }

    override fun onResume() {
        super.onResume()
        sync()
    }

    /** 앱이 보일 때마다: 지난 날 마감 → 오늘 판정 → 알람·지오펜스 재등록 → 필요하면 감지 시작 */
    fun sync() {
        runCatching {
            Engine.settlePast(this)
            Engine.checkToday(this)
            Scheduler.scheduleAll(this)
            Geo.register(this)
            if (Scheduler.shouldTrackNow(this)) Scheduler.startTracking(this)
        }
        runCatching { Updater.autoCheck(this, notify = false) { runOnUiThread { version.intValue++ } } }
        version.intValue++
    }
}

@Composable
fun AppRoot(version: Int, bump: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    val tabs = listOf("홈" to Icons.Filled.Home, "근무기록" to Icons.Filled.DateRange,
        "급여" to Icons.Filled.List, "설정" to Icons.Filled.Settings)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i },
                        icon = { Icon(icon, label) }, label = { Text(label) })
                }
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        when (tab) {
            0 -> HomeScreen(version, bump, m, goSettings = { tab = 3 }, goRecords = { tab = 1 })
            1 -> RecordsScreen(version, bump, month, { month = it }, m)
            2 -> PayScreen(version, bump, month, { month = it }, m)
            else -> SettingsScreen(version, bump, m)
        }
    }
}
