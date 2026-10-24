package kr.worklife.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 미니앱 목록 (새 기능은 여기에 한 줄 추가하면 '앱' 탭에 나타남) */
data class MiniApp(val id: String, val icon: String, val name: String, val desc: String, val tint: Color, val ready: Boolean = true)

val MINI_APPS = listOf(
    MiniApp("lotto", "🎱", "로또", "번호 생성·QR 당첨확인·통계", Color(0xFFFFF3C4)),
    MiniApp("lunch", "🍱", "점심 룰렛", "오늘 뭐 먹지?", Color(0xFFFFE1D6)),
    MiniApp("wheel", "🎡", "돌림판", "내기·순서 정하기", Color(0xFFE3F0FF)),
    MiniApp("ladder", "🪜", "사다리", "2~8명 사다리타기", Color(0xFFE6F7EC)),
    MiniApp("stock", "📈", "주식 시세", "관심종목·지수", Color(0xFFEDE7FF), ready = false),
    MiniApp("bus", "🚌", "대중교통", "버스 도착 정보", Color(0xFFDDF6F3), ready = false),
    MiniApp("map", "🗺", "지도", "즐겨찾기·주변 검색", Color(0xFFFFE8F0), ready = false),
)

/** '앱' 탭: 미니앱 격자 → 누르면 그 앱 화면 (← 또는 뒤로가기로 목록) */
@Composable
fun AppsScreen(modifier: Modifier, openApp: String?, setOpen: (String?) -> Unit) {
    when (openApp) {
        "lotto" -> { LottoScreen(modifier) { setOpen(null) }; return }
        "lunch", "wheel", "ladder" -> {
            FunScreen(modifier, initial = listOf("lunch", "wheel", "ladder").indexOf(openApp)) { setOpen(null) }; return
        }
    }
    var notice by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BigHeader("앱", "자주 쓰는 기능을 한곳에서")
        MINI_APPS.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { a ->
                    Column(Modifier.weight(1f).height(128.dp).background(a.tint, RoundedCornerShape(24.dp))
                        .clickable { if (a.ready) setOpen(a.id) else notice = "${a.name}은(는) 곧 추가돼요" }.padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).background(Color.White.copy(alpha = .8f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Text(a.icon, fontSize = 24.sp) }
                            Spacer(Modifier.weight(1f))
                            if (!a.ready) Text("준비 중", fontSize = 11.sp, color = Color(0xFF6B7280),
                                modifier = Modifier.background(Color.White.copy(alpha = .7f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                        Column {
                            Text(a.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111827))
                            Text(a.desc, fontSize = 12.sp, color = Color(0xFF4B5563), maxLines = 1)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        notice?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
        Spacer(Modifier.height(24.dp))
    }
}
