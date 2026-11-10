package kr.worklife.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.worklife.bg.Geo
import kr.worklife.bg.TravelMode
import kr.worklife.bg.TravelPhotos
import kr.worklife.core.*
import kr.worklife.data.Repo
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/** 알림·바로가기에서 여행 화면 열기 ("record" / "expense" / "photo" / "home") */
object TravelNav {
    val pendingApp = mutableStateOf<String?>(null)
    var action by mutableStateOf<String?>(null)
}

/** 미니앱 안쪽 화면의 '뒤로' (true = 처리함). 없으면 앱 목록으로 */
object AppBack {
    var handler: (() -> Boolean)? = null
}

/** 사진 썸네일 (메모리 캐시) */
private object Thumbs {
    val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount }
    fun load(path: String, px: Int): Bitmap? {
        val key = "$path@$px"
        cache.get(key)?.let { return it }
        return runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            var s = 1
            while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })?.also { cache.put(key, it) }
        }.getOrNull()
    }
}

@Composable
fun Thumb(path: String, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, px: Int = 300, onClick: (() -> Unit)? = null) {
    val bmp by produceState<Bitmap?>(null, path, px) { value = withContext(Dispatchers.IO) { Thumbs.load(path, px) } }
    val m = modifier.size(size).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
    Box(if (onClick != null) m.clickable { onClick() } else m, contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: Text("🖼", fontSize = 18.sp)
    }
}

@Composable
fun Stars(value: Double, size: Int = 22, onChange: ((Double) -> Unit)? = null) {
    Row {
        for (i in 1..5) {
            val on = value >= i
            Text(if (on) "★" else "☆", fontSize = size.sp, color = if (on) Color(0xFFFFB400) else MaterialTheme.colorScheme.onSurface.copy(alpha = .3f),
                modifier = if (onChange != null) Modifier.clickable { onChange(if (value == i.toDouble()) 0.0 else i.toDouble()) }.padding(end = 2.dp) else Modifier)
        }
    }
}

private fun money(v: Long) = "%,d원".format(v)
private fun shortDate(d: LocalDate) = "${d.monthValue}/${d.dayOfMonth}(${wd(d)})"

private fun openUrl(ctx: Context, url: String) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
private fun openMap(ctx: Context, lat: Double, lon: Double, label: String) =
    openUrl(ctx, "geo:$lat,$lon?q=$lat,$lon(${Uri.encode(label)})")

private fun fileUri(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)

/** 카톡 등으로 공유: 글 + 사진(최대 10장) */
private fun shareTrip(ctx: Context, t: Trip, spots: List<Spot>, ex: List<Expense>) {
    val text = Travel.shareText(t, spots, ex)
    val pics = spots.sortedBy { it.ts }.flatMap { it.photos }.map { File(it) }.filter { it.exists() }.take(10)
    val i = if (pics.isEmpty()) Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    else Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").putExtra(Intent.EXTRA_TEXT, text)
        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(pics.map { fileUri(ctx, it) })).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(Intent.createChooser(i, "여행 공유")) }
}

// ─────────────────────────────── 여행 첫 화면 ───────────────────────────────

@Composable
fun TravelScreen(modifier: Modifier, back: () -> Unit) {
    val ctx = LocalContext.current
    val repo = Repo.get(ctx)
    var ver by remember { mutableIntStateOf(0) }
    var openId by rememberSaveable { mutableLongStateOf(-1L) }
    var newTrip by remember { mutableStateOf(false) }

    // 알림 [기록]/[경비]/[사진] → 진행 중 여행 열고 바로 그 창
    val act = TravelNav.action
    LaunchedEffect(act) {
        if (act != null) {
            val a = repo.activeTrip()
            if (a != null) openId = a.id else if (act != "home") newTrip = true
        }
    }
    DisposableEffect(openId) {
        AppBack.handler = if (openId > 0) ({ openId = -1L; ver++; true }) else null
        onDispose { AppBack.handler = null }
    }
    if (openId > 0) {
        val t = remember(openId, ver) { repo.trip(openId) }
        if (t != null) {
            TripDetail(modifier, t, { ver++ }) { openId = -1; ver++ }
            return
        } else openId = -1
    }

    val trips = remember(ver) { repo.trips() }
    val spotsBy = remember(ver) { repo.spots().groupBy { it.tripId } }
    val exBy = remember(ver) { repo.expenses().groupBy { it.tripId } }
    val active = trips.firstOrNull { it.active }
    var q by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var fav by rememberSaveable { mutableStateOf(false) }
    var good by rememberSaveable { mutableStateOf(false) }
    val shown = Travel.search(trips, spotsBy, q, tag, fav, if (good) 4.0 else 0.0)
    val memories = remember(ver) { Travel.onThisDay(trips, LocalDate.now()) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back) { Text("←", fontSize = 22.sp) }
            Text("✈️ 여행", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (active == null) FilledTonalButton(onClick = { newTrip = true }) { Text("+ 새 여행") }
        }

        if (active != null) {
            val today = LocalDate.now()
            val sp = spotsBy[active.id].orEmpty(); val ex = exBy[active.id].orEmpty()
            SCard(color = Color(0xFFDDF6F3), onClick = { openId = active.id }) {
                Text("여행 중 · Day ${active.days().size}", fontSize = 12.sp, color = Color(0xFF0F766E), fontWeight = FontWeight.Bold)
                Text(active.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111827))
                Text("오늘 ${sp.count { it.ts.toLocalDate() == today }}곳 · 경비 ${money(ex.filter { it.ts.toLocalDate() == today }.sumOf { it.amount })} · 전체 ${money(Travel.summarize(ex).total)}",
                    fontSize = 13.sp, color = Color(0xFF374151))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { TravelNav.action = "record"; openId = active.id }) { Text("📍 기록") }
                    FilledTonalButton(onClick = { TravelNav.action = "expense"; openId = active.id }) { Text("💸 경비") }
                    OutlinedButton(onClick = { TravelMode.end(ctx, active); ver++ }) { Text("여행 끝") }
                }
            }
        } else SCard(onClick = { newTrip = true }) {
            Text("여행을 떠나시나요?", fontWeight = FontWeight.SemiBold)
            Text("[여행 시작]을 누르면 알림창에서 바로 기록·경비·사진을 남기고, 이동 경로와 카드 결제가 자동으로 모여요.", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
        }

        memories.forEach { (t, y) ->
            SCard(color = Color(0xFFFFF3C4), onClick = { openId = t.id }, pad = 16.dp) {
                Text("📅 ${y}년 전 오늘", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF92400E))
                Text(t.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111827))
                val ph = spotsBy[t.id].orEmpty().flatMap { it.photos }.take(4)
                if (ph.isNotEmpty()) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { ph.forEach { Thumb(it, 64.dp) } }
            }
        }

        if (trips.isNotEmpty()) {
            OutlinedTextField(q, { q = it }, placeholder = { Text("검색 (장소·메뉴·메모·#태그)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
            val tags = trips.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(12).map { it.key }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(fav, { fav = !fav }, { Text("♥ 다시 가기") })
                FilterChip(good, { good = !good }, { Text("★4 이상") })
                tags.forEach { tg -> FilterChip(tag == tg, { tag = if (tag == tg) null else tg }, { Text("#$tg") }) }
            }
        }

        shown.forEach { t ->
            val sp = spotsBy[t.id].orEmpty(); val ex = exBy[t.id].orEmpty()
            SCard(onClick = { openId = t.id }, pad = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val cover = t.cover.ifBlank { sp.flatMap { it.photos }.firstOrNull() ?: "" }
                    if (cover.isNotBlank()) { Thumb(cover, 64.dp); Spacer(Modifier.width(12.dp)) }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                            if (t.fav) Text(" ♥", color = Color(0xFFE11D48))
                            if (t.active) { Spacer(Modifier.width(6.dp)); Tag("여행 중", OkGreen) }
                        }
                        Text(t.periodText + (if (t.companions.isNotBlank()) " · ${t.companions}" else ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                        Text("${sp.size}곳 · ${money(Travel.summarize(ex).total)}" + (if (t.tags.isNotEmpty()) " · " + t.tags.take(3).joinToString(" ") { "#$it" } else ""),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), maxLines = 1)
                    }
                    if (t.rating > 0) Text("★${"%.1f".format(t.rating)}", color = Color(0xFFFFB400), fontWeight = FontWeight.Bold)
                }
            }
        }
        if (trips.isNotEmpty() && shown.isEmpty()) Text("찾는 여행이 없어요", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))

        TravelSettingsCard(ctx)
        Spacer(Modifier.height(24.dp))
    }

    if (newTrip) TripEditDialog(null, { newTrip = false; if (TravelNav.action != null && repo.activeTrip() == null) TravelNav.action = null }) { t, startNow ->
        newTrip = false
        val id = if (startNow) TravelMode.start(ctx, t) else repo.saveTrip(t)
        ver++; openId = id
    }
}

@Composable
private fun TravelSettingsCard(ctx: Context) {
    val repo = Repo.get(ctx)
    var open by remember { mutableStateOf(false) }
    var min by remember { mutableIntStateOf(repo.travelTrackMin) }
    var daily by remember { mutableStateOf(repo.travelDailyNote) }
    var mem by remember { mutableStateOf(repo.travelMemory) }
    SCard(pad = 16.dp) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Text("⚙️ 여행 설정", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(if (open) "▲" else "▼", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
        }
        if (open) {
            Text("자동 경로 (여행 중 위치 기록 간격)", fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "끔", 15 to "15분", 30 to "30분", 60 to "1시간").forEach { (v, l) ->
                    FilterChip(min == v, { min = v; repo.travelTrackMin = v; TravelMode.scheduleAll(ctx) }, { Text(l) })
                }
            }
            SwitchRow("밤 9시 하루 정리 알림", daily) { daily = it; repo.travelDailyNote = it; TravelMode.scheduleAll(ctx) }
            SwitchRow("'1년 전 오늘' 추억 알림 (오전 9시)", mem) { mem = it; repo.travelMemory = it; TravelMode.scheduleAll(ctx) }
            Text("사진은 폰 안에만 저장돼요. 설정 > 백업 > [내보내기]로 사진까지 한 파일로 보관할 수 있어요.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        }
    }
}

// ─────────────────────────────── 여행 상세 ───────────────────────────────

@Composable
private fun TripDetail(modifier: Modifier, t: Trip, changed: () -> Unit, close: () -> Unit) {
    val ctx = LocalContext.current
    val repo = Repo.get(ctx)
    var ver by remember { mutableIntStateOf(0) }
    val spots = remember(t, ver) { repo.spots(t.id) }
    val ex = remember(t, ver) { repo.expenses(t.id) }
    val track = remember(t, ver) { repo.track(t.id) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editSpot by remember { mutableStateOf<Spot?>(null) }
    var newSpot by remember { mutableStateOf(false) }
    var newSpotPhoto by remember { mutableStateOf(false) }
    var editEx by remember { mutableStateOf<Expense?>(null) }
    var newEx by remember { mutableStateOf(false) }
    var editTrip by remember { mutableStateOf(false) }
    var askDel by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    fun refresh() { ver++; changed() }

    // 알림 바로가기
    LaunchedEffect(TravelNav.action) {
        when (TravelNav.action) {
            "record" -> newSpot = true
            "photo" -> { newSpotPhoto = true; newSpot = true }
            "expense" -> { tab = 2; newEx = true }
        }
        TravelNav.action = null
    }

    // 사진 여러 장 → 찍은 위치·시간으로 묶어서 장소 자동 생성
    var importing by remember { mutableStateOf(false) }
    val bulk = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        Thread {
            val ps = TravelPhotos.import(ctx, t.id, uris)
            val groups = Travel.groupPhotos(ps)
            groups.forEach { g ->
                val f = g.first()
                repo.saveSpot(Spot(tripId = t.id, kind = SpotKind.PLACE, ts = f.ts ?: LocalDateTime.now(), lat = g.map { it.lat }.average(),
                    lon = g.map { it.lon }.average(), photos = g.map { it.path }))
            }
            val noLoc = ps.filter { it.lat == 0.0 && it.lon == 0.0 }
            noLoc.groupBy { it.ts?.toLocalDate() ?: t.start }.forEach { (d, l) ->
                repo.saveSpot(Spot(tripId = t.id, kind = SpotKind.PLACE, name = "사진 (위치 없음)", ts = l.first().ts ?: d.atTime(12, 0), photos = l.map { it.path }))
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                importing = false
                msg = "사진 ${ps.size}장 → 장소 ${groups.size + noLoc.groupBy { it.ts?.toLocalDate() }.size}곳으로 묶었어요" +
                    (if (ps.size < uris.size) " (못 읽은 사진 ${uris.size - ps.size}장)" else "") + ". 이름·별점은 눌러서 채워 주세요"
                refresh()
            }
        }.start()
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = close) { Text("←", fontSize = 22.sp) }
            Text(t.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { repo.saveTrip(t.copy(fav = !t.fav)); refresh() }) { Text(if (t.fav) "♥" else "♡", fontSize = 20.sp, color = Color(0xFFE11D48)) }
            TextButton(onClick = { shareTrip(ctx, t, spots, ex) }) { Text("공유") }
            TextButton(onClick = { editTrip = true }) { Text("편집") }
        }
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(t.periodText + (if (t.companions.isNotBlank()) " · ${t.companions}" else ""), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Stars(t.rating) { repo.saveTrip(t.copy(rating = it)); refresh() }
                if (t.tags.isNotEmpty()) Text("  " + t.tags.joinToString(" ") { "#$it" }, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
            if (t.memo.isNotBlank()) Text(t.memo, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        }
        val sm = Travel.summarize(ex)
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (t.active) {
                Button(onClick = { newSpot = true }, Modifier.weight(1f)) { Text("📍 기록") }
                FilledTonalButton(onClick = { tab = 2; newEx = true }, Modifier.weight(1f)) { Text("💸 경비") }
                OutlinedButton(onClick = { TravelMode.end(ctx, t); refresh() }) { Text("여행 끝") }
            } else {
                OutlinedButton(onClick = { newSpot = true }, Modifier.weight(1f)) { Text("+ 장소") }
                OutlinedButton(onClick = { bulk.launch(arrayOf("image/*")) }, Modifier.weight(1f)) { Text(if (importing) "가져오는 중…" else "🖼 사진으로") }
            }
        }
        msg?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 24.dp)) }

        TabRow(tab, Modifier.padding(top = 4.dp), containerColor = Color.Transparent) {
            listOf("일정표", "장소 ${spots.size}", "경비", "지도").forEachIndexed { i, l -> Tab(tab == i, { tab = i }, text = { Text(l, maxLines = 1) }) }
        }

        when (tab) {
            0 -> Timeline(t, spots, ex, track, { editSpot = it }, { editEx = it }, { viewer = it })
            1 -> SpotList(t, spots, { editSpot = it }, { viewer = it }, { bulk.launch(arrayOf("image/*")) }, importing)
            2 -> ExpenseTab(t, ex, sm, { editEx = it }, { newEx = true }) { refresh() }
            else -> TripMap(spots, track)
        }
        if (!t.active) TextButton(onClick = { askDel = true }, Modifier.padding(horizontal = 16.dp)) {
            Text("이 여행 삭제", color = MaterialTheme.colorScheme.error.copy(alpha = .8f), fontSize = 13.sp)
        } else TextButton(onClick = { askDel = true }, Modifier.padding(horizontal = 16.dp)) {
            Text("이 여행 삭제", color = MaterialTheme.colorScheme.error.copy(alpha = .6f), fontSize = 12.sp)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (newSpot || editSpot != null) SpotDialog(t, editSpot, startCamera = newSpotPhoto, onDismiss = { newSpot = false; newSpotPhoto = false; editSpot = null },
        onDelete = { s -> repo.deleteSpot(s.id); editSpot = null; refresh() }) { s, alsoExpense ->
        val id = repo.saveSpot(s)
        if (alsoExpense && s.price > 0) repo.saveExpense(Expense(tripId = t.id, ts = s.ts, amount = s.price, memo = s.title,
            category = when (s.kind) { SpotKind.FOOD -> "식비"; SpotKind.STAY -> "숙박"; SpotKind.SIGHT -> "입장료"; else -> "기타" }))
        newSpot = false; newSpotPhoto = false; editSpot = null
        msg = if (s.id == 0L) "${SpotKind.icon(s.kind)} ${s.title} 기록했어요" else null
        if (id > 0) refresh()
        if (t.active) TravelMode.showOngoing(ctx)
    }
    if (newEx || editEx != null) ExpenseDialog(t, editEx, { newEx = false; editEx = null }, { e -> repo.deleteExpense(e.id); editEx = null; refresh() }) { e ->
        repo.saveExpense(e); newEx = false; editEx = null; refresh()
        if (t.active) TravelMode.showOngoing(ctx)
    }
    if (editTrip) TripEditDialog(t, { editTrip = false }) { nt, startNow ->
        editTrip = false
        if (startNow && !t.active) TravelMode.start(ctx, nt) else repo.saveTrip(nt)
        refresh()
    }
    if (askDel) AlertDialog(onDismissRequest = { askDel = false }, title = { Text("'${t.title}' 삭제") },
        text = { Text("장소 ${spots.size}곳·경비 ${ex.size}건·사진이 모두 지워져요. 되돌릴 수 없어요.") },
        confirmButton = { TextButton(onClick = {
            if (t.active) TravelMode.end(ctx, t)
            repo.deleteTrip(t.id, ctx.filesDir); askDel = false; TravelMode.scheduleAll(ctx); close()
        }) { Text("삭제", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { askDel = false }) { Text("취소") } })
    viewer?.let { p ->
        Dialog(onDismissRequest = { viewer = null }) {
            Box(Modifier.fillMaxWidth().clickable { viewer = null }) { Thumb(p, 360.dp, Modifier.fillMaxWidth(), px = 1600) }
        }
    }
}

@Composable
private fun Timeline(t: Trip, spots: List<Spot>, ex: List<Expense>, track: List<TrackPoint>, onSpot: (Spot) -> Unit, onEx: (Expense) -> Unit, view: (String) -> Unit) {
    val ctx = LocalContext.current
    val days = Travel.timeline(t, spots, ex, track)
    if (spots.isEmpty() && ex.isEmpty()) {
        Text("아직 기록이 없어요. [📍 기록]으로 지금 있는 곳을 남겨 보세요.", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        return
    }
    Travel.courseUrl(spots)?.let { url ->
        OutlinedButton(onClick = { openUrl(ctx, url) }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { Text("🧭 그 코스 그대로 길찾기") }
    }
    days.forEachIndexed { i, d ->
        SCard(pad = 16.dp) {
            CardTitle("Day ${i + 1} · ${shortDate(d.date)}", trailing = {
                Text((if (d.km >= 1) "%.0fkm · ".format(d.km) else "") + money(d.spend), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
            })
            if (d.entries.isEmpty()) Text("기록 없음", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f))
            d.entries.forEach { e ->
                Row(Modifier.fillMaxWidth().clickable { e.spot?.let(onSpot) ?: e.expense?.let(onEx) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(e.ts.toLocalTime().hhmm(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f), modifier = Modifier.width(44.dp))
                    val s = e.spot
                    if (s != null) {
                        Column(Modifier.weight(1f)) {
                            Text("${SpotKind.icon(s.kind)} ${s.title}" + (if (s.again) " ♥" else ""), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = listOfNotNull(s.transport.takeIf { it.isNotBlank() }?.let { "$it 이동" }, s.menu.takeIf { it.isNotBlank() },
                                s.price.takeIf { it > 0 }?.let { money(it) }, s.memo.takeIf { it.isNotBlank() }?.take(40)).joinToString(" · ")
                            if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), maxLines = 1)
                            if (s.photos.isNotEmpty()) Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                s.photos.take(6).forEach { p -> Thumb(p, 52.dp) { view(p) } }
                            }
                        }
                        if (s.rating > 0) Text("★${"%.1f".format(s.rating)}", fontSize = 13.sp, color = Color(0xFFFFB400), fontWeight = FontWeight.Bold)
                    } else e.expense?.let { x ->
                        Text("${ExpenseCat.icon(x.category)} ${x.memo.ifBlank { x.category }}", fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(money(x.amount), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SpotList(t: Trip, spots: List<Spot>, onSpot: (Spot) -> Unit, view: (String) -> Unit, importPhotos: () -> Unit, importing: Boolean) {
    val ctx = LocalContext.current
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(kind == null, { kind = null }, { Text("전체") })
        SpotKind.all.forEach { k -> val n = spots.count { it.kind == k }; if (n > 0) FilterChip(kind == k, { kind = if (kind == k) null else k }, { Text("${SpotKind.icon(k)} ${SpotKind.label(k)} $n") }) }
        FilterChip(kind == "again", { kind = if (kind == "again") null else "again" }, { Text("♥ 다시 가기") })
    }
    if (t.active) TextButton(onClick = importPhotos, Modifier.padding(horizontal = 16.dp)) { Text(if (importing) "사진 가져오는 중…" else "🖼 갤러리 사진으로 장소 만들기 (찍은 위치·시간으로 묶기)", fontSize = 13.sp) }
    val list = spots.filter { kind == null || it.kind == kind || (kind == "again" && it.again) }.sortedWith(compareByDescending<Spot> { it.rating }.thenBy { it.ts })
    list.forEach { s ->
        SCard(onClick = { onSpot(s) }, pad = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${SpotKind.icon(s.kind)} ${s.title}" + (if (s.again) " ♥" else ""), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(shortDate(s.ts.toLocalDate()) + " " + s.ts.toLocalTime().hhmm() +
                        (if (s.kind == SpotKind.STAY && s.stayType.isNotBlank()) " · ${s.stayType}" else "") +
                        (if (s.kind == SpotKind.STAY && s.nights > 0) " ${s.nights}박" else "") +
                        (if (s.price > 0) " · ${money(s.price)}" else ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                    if (s.menu.isNotBlank()) Text(s.menu, fontSize = 13.sp)
                    if (s.memo.isNotBlank()) Text(s.memo, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .8f))
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (s.rating > 0) Stars(s.rating, 14)
                    if (s.hasLocation) TextButton(onClick = { openMap(ctx, s.lat, s.lon, s.title) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("지도", fontSize = 12.sp) }
                }
            }
            if (s.photos.isNotEmpty()) Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.photos.forEach { p -> Thumb(p, 72.dp) { view(p) } }
            }
        }
    }
    if (list.isEmpty()) Text("기록한 장소가 없어요", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
}

@Composable
private fun ExpenseTab(t: Trip, ex: List<Expense>, sm: Travel.ExpenseSummary, onEx: (Expense) -> Unit, add: () -> Unit, changed: () -> Unit) {
    val ctx = LocalContext.current
    val repo = Repo.get(ctx)
    var pick by remember { mutableStateOf(false) }
    val cands = remember(t, ex, pick) { Travel.cardCandidates(t, repo.cardTxs(t.start, t.end ?: maxOf(LocalDate.now(), t.start)), ex) }
    SCard(pad = 16.dp) {
        CardTitle("경비 합계", trailing = { Text(money(sm.total), fontSize = 20.sp, fontWeight = FontWeight.Bold) })
        sm.byCat.entries.sortedByDescending { it.value }.forEach { (c, v) ->
            val r = if (sm.total > 0) (v.toFloat() / sm.total).coerceIn(0f, 1f) else 0f
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${ExpenseCat.icon(c)} $c", fontSize = 13.sp, modifier = Modifier.width(78.dp))
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .07f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(r).background(MaterialTheme.colorScheme.primary))
                }
                Text(money(v), fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp).widthIn(min = 80.dp))
            }
        }
        if (sm.total > 0) Text("직접 입력 ${money(sm.manual)} · 카드 ${money(sm.card)}" + (t.companions.takeIf { it.isNotBlank() }?.let { c ->
            val n = c.split(',', '·', ' ', '/').count { it.isNotBlank() } + 1
            if (n > 1) " · ${n}명이면 1인 ${money(sm.total / n)}" else ""
        } ?: ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = add) { Text("+ 경비 입력") }
            OutlinedButton(onClick = { pick = true }, enabled = cands.isNotEmpty()) { Text("💳 카드에서 불러오기" + if (cands.isNotEmpty()) " ${cands.size}" else "") }
        }
    }
    ex.sortedByDescending { it.ts }.groupBy { it.ts.toLocalDate() }.forEach { (d, l) ->
        Text("${shortDate(d)} · ${money(l.sumOf { it.amount })}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 24.dp, top = 10.dp))
        SCard(pad = 12.dp) {
            l.forEach { x ->
                Row(Modifier.fillMaxWidth().clickable { onEx(x) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(ExpenseCat.icon(x.category), fontSize = 18.sp, modifier = Modifier.width(30.dp))
                    Column(Modifier.weight(1f)) {
                        Text(x.memo.ifBlank { x.category }, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${x.ts.toLocalTime().hhmm()} · ${x.category} · ${x.method}" + (if (x.cardTxId > 0) " (카드 연동)" else ""), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
                    }
                    Text(money(x.amount), fontWeight = FontWeight.SemiBold, color = if (x.amount < 0) OkGreen else Color.Unspecified)
                }
            }
        }
    }
    if (pick) {
        val sel = remember { mutableStateListOf<Long>().apply { addAll(cands.map { it.id }) } }
        AlertDialog(onDismissRequest = { pick = false }, title = { Text("여행 기간 카드 결제 ${cands.size}건") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("경비에 넣을 결제를 고르세요 (이미 넣은 건 빠져 있어요)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                    cands.forEach { c ->
                        Row(Modifier.fillMaxWidth().clickable { if (c.id in sel) sel.remove(c.id) else sel.add(c.id) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(c.id in sel, { if (it) sel.add(c.id) else sel.remove(c.id) })
                            Column(Modifier.weight(1f)) {
                                Text(c.merchant.ifBlank { "카드 결제" }, fontSize = 14.sp, maxLines = 1)
                                Text("${shortDate(c.ts.toLocalDate())} ${c.ts.toLocalTime().hhmm()} · ${ExpenseCat.guess(c.merchant)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
                            }
                            Text(money(c.amount), fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                cands.filter { it.id in sel }.forEach { repo.saveExpense(Travel.fromCard(t, it)) }
                pick = false; changed()
            }) { Text("${sel.size}건 넣기") } },
            dismissButton = { TextButton(onClick = { pick = false }) { Text("취소") } })
    }
}

/** 지도: 장소 핀 + 자동 경로 (OpenStreetMap, 키 불필요) */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TripMap(spots: List<Spot>, track: List<TrackPoint>) {
    val ctx = LocalContext.current
    val json = Travel.mapJson(spots, track)
    if (spots.none { it.hasLocation } && track.isEmpty()) {
        Text("위치가 있는 기록이 없어요", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f)); return
    }
    val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>html,body,#m{height:100%;margin:0}</style></head><body><div id="m"></div><script>
var d=$json;
try{var m=L.map('m');L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(m);
var b=[];d.pins.forEach(function(p,i){L.marker([p.lat,p.lon]).addTo(m).bindPopup('<b>'+(i+1)+'. '+p.t+'</b><br>'+p.s);b.push([p.lat,p.lon]);});
if(d.line.length>1){L.polyline(d.line,{color:'#3E91FF',weight:4,opacity:.7}).addTo(m);d.line.forEach(function(x){b.push(x)});}
if(b.length==1)m.setView(b[0],15);else m.fitBounds(b,{padding:[30,30]});}catch(e){document.body.innerHTML='<p style="padding:20px">지도를 불러오지 못했어요 (인터넷 연결 확인)</p>';}
</script></body></html>"""
    Box(Modifier.fillMaxWidth().height(440.dp).padding(horizontal = 14.dp, vertical = 8.dp).clip(RoundedCornerShape(20.dp))) {
        AndroidView(factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = true
                webViewClient = WebViewClient()
            }
        }, update = { it.loadDataWithBaseURL("https://worklife.local/", html, "text/html", "utf-8", null) }, modifier = Modifier.fillMaxSize())
    }
    Text("핀을 누르면 이름이 보여요 · 파란 선은 자동 기록된 이동 경로", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f),
        modifier = Modifier.padding(horizontal = 24.dp))
    Travel.courseUrl(spots)?.let { url -> TextButton(onClick = { openUrl(ctx, url) }, Modifier.padding(horizontal = 16.dp)) { Text("🧭 구글 지도로 코스 길찾기") } }
}

// ─────────────────────────────── 입력 창 ───────────────────────────────

@Composable
private fun TripEditDialog(t: Trip?, onDismiss: () -> Unit, onSave: (Trip, Boolean) -> Unit) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf(t?.title ?: "") }
    var start by remember { mutableStateOf(t?.start ?: LocalDate.now()) }
    var end by remember { mutableStateOf(t?.end) }
    var comp by remember { mutableStateOf(t?.companions ?: "") }
    var tags by remember { mutableStateOf(t?.tags?.joinToString(" ") { "#$it" } ?: "") }
    var memo by remember { mutableStateOf(t?.memo ?: "") }
    var now by remember { mutableStateOf(t == null || t.active) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (t == null) "새 여행" else "여행 편집") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(title, { title = it }, label = { Text("여행 이름 (예: 제주 가족여행)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (t == null || !t.active) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    FilterChip(now, { now = true; end = null }, { Text("지금 떠나요 (여행 중 모드)") })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(!now, { now = false }, { Text("지난 여행") })
                }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickDate(ctx, start) { start = it; if (end?.isBefore(it) == true) end = it } }) { Text("시작 ${shortDate(start)}") }
                    if (!now) OutlinedButton(onClick = { pickDate(ctx, end ?: start) { end = if (it.isBefore(start)) start else it } }) { Text("끝 ${end?.let { shortDate(it) } ?: "-"}") }
                }
                OutlinedTextField(comp, { comp = it }, label = { Text("누구와 (예: 아내, 아이)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(tags, { tags = it }, label = { Text("태그 (예: #제주 #가족)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(memo, { memo = it }, label = { Text("메모") }, modifier = Modifier.fillMaxWidth())
                if (now && t == null) Text("여행 중에는 알림창에 [기록]·[경비]·[사진] 버튼이 생기고, 이동 경로와 카드 결제가 자동으로 모여요.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = {
            val base = t ?: Trip(title = title.trim(), start = start)
            onSave(base.copy(title = title.trim(), start = start, end = if (now) null else (end ?: start), companions = comp.trim(),
                tags = Travel.parseTags(tags), memo = memo.trim()), now && t?.active != true)
        }) { Text(if (t == null && now) "여행 시작" else "저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}

@Composable
private fun SpotDialog(t: Trip, s0: Spot?, startCamera: Boolean, onDismiss: () -> Unit, onDelete: (Spot) -> Unit, onSave: (Spot, Boolean) -> Unit) {
    val ctx = LocalContext.current
    val isNew = s0 == null
    var kind by remember { mutableStateOf(s0?.kind ?: SpotKind.FOOD) }
    var name by remember { mutableStateOf(s0?.name ?: "") }
    var rating by remember { mutableStateOf(s0?.rating ?: 0.0) }
    var price by remember { mutableStateOf(s0?.price?.takeIf { it > 0 }?.toString() ?: "") }
    var menu by remember { mutableStateOf(s0?.menu ?: "") }
    var memo by remember { mutableStateOf(s0?.memo ?: "") }
    var again by remember { mutableStateOf(s0?.again ?: false) }
    var stayType by remember { mutableStateOf(s0?.stayType ?: "") }
    var nights by remember { mutableStateOf(s0?.nights?.takeIf { it > 0 }?.toString() ?: "") }
    var booking by remember { mutableStateOf(s0?.booking ?: "") }
    var transport by remember { mutableStateOf(s0?.transport ?: "") }
    var lat by remember { mutableStateOf(s0?.lat ?: 0.0) }
    var lon by remember { mutableStateOf(s0?.lon ?: 0.0) }
    var ts by remember { mutableStateOf(s0?.ts ?: (if (t.active || t.contains(LocalDate.now())) LocalDateTime.now().withSecond(0).withNano(0) else t.start.atTime(12, 0))) }
    val photos = remember { mutableStateListOf<String>().apply { addAll(s0?.photos.orEmpty()) } }
    var locMsg by remember { mutableStateOf(if (isNew) "위치 잡는 중…" else "") }
    var addExpense by remember { mutableStateOf(true) }
    var askDel by remember { mutableStateOf(false) }

    fun useLoc() {
        locMsg = "위치 잡는 중…"
        Geo.current(ctx) { l ->
            if (l != null) { lat = l.latitude; lon = l.longitude; locMsg = "📍 지금 위치 (±%.0fm)".format(l.accuracy) }
            else locMsg = "위치를 못 잡았어요 (사진에 위치가 있으면 그걸 써요)"
        }
    }
    LaunchedEffect(Unit) { if (isNew && t.active) useLoc() else if (isNew) locMsg = "" }

    fun applyPhoto(p: Travel.PhotoInfo) {
        photos.add(p.path)
        if (lat == 0.0 && lon == 0.0 && (p.lat != 0.0 || p.lon != 0.0)) { lat = p.lat; lon = p.lon; locMsg = "📍 사진 위치" }
        if (isNew && !t.active && p.ts != null && photos.size == 1) ts = p.ts
    }
    var camFile by remember { mutableStateOf<File?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = camFile
        if (ok && f != null && f.exists() && f.length() > 0) Thread {
            val p = TravelPhotos.fromCameraFile(ctx, f)
            android.os.Handler(android.os.Looper.getMainLooper()).post { if (p != null) applyPhoto(p) else photos.add(f.absolutePath) }
        }.start() else f?.delete()
    }
    fun shoot() {
        val f = TravelPhotos.newFile(ctx, t.id)
        camFile = f
        runCatching { camera.launch(fileUri(ctx, f)) }.onFailure { locMsg = "카메라를 열 수 없어요" }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) Thread {
            val ps = TravelPhotos.import(ctx, t.id, uris)
            android.os.Handler(android.os.Looper.getMainLooper()).post { ps.forEach { applyPhoto(it) } }
        }.start()
    }
    LaunchedEffect(Unit) { if (startCamera) shoot() }

    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (isNew) "지금 여기 기록" else "기록 고치기") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SpotKind.all.forEach { k -> FilterChip(kind == k, { kind = k }, { Text("${SpotKind.icon(k)} ${SpotKind.label(k)}") }) }
                }
                OutlinedTextField(name, { name = it }, label = { Text(when (kind) { SpotKind.FOOD -> "가게 이름"; SpotKind.STAY -> "숙소 이름"; else -> "장소 이름" }) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Stars(rating, 28) { rating = it }
                    Spacer(Modifier.weight(1f))
                    FilterChip(again, { again = !again }, { Text(if (again) "♥ 다시 가기" else "♡ 다시 가기") })
                }
                if (kind == SpotKind.FOOD) OutlinedTextField(menu, { menu = it }, label = { Text("먹은 메뉴") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (kind == SpotKind.STAY) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("호텔", "펜션", "리조트", "게스트하우스", "캠핑", "기타").forEach { v -> FilterChip(stayType == v, { stayType = if (stayType == v) "" else v }, { Text(v) }) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(nights, { nights = it.filter(Char::isDigit).take(2) }, label = { Text("몇 박") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(booking, { booking = it }, label = { Text("예약처") }, singleLine = true, modifier = Modifier.weight(2f))
                    }
                }
                OutlinedTextField(price, { price = it.filter(Char::isDigit).take(9) }, label = { Text("금액 (원)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                if (isNew && price.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(addExpense, { addExpense = it }); Text("경비에도 넣기 (카드 결제가 따로 들어오면 끄세요)", fontSize = 12.sp)
                }
                OutlinedTextField(memo, { memo = it }, label = { Text("한 줄 메모 (#태그 가능)") }, modifier = Modifier.fillMaxWidth())
                Text("여기까지 이동", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("🚗 차", "🚶 도보", "🚌 버스", "🚆 기차", "✈️ 비행기", "🚕 택시", "⛴ 배").forEach { v ->
                        val key = v.substringAfter(' ')
                        FilterChip(transport == key, { transport = if (transport == key) "" else key }, { Text(v) })
                    }
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { shoot() }) { Text("📷 촬영") }
                    OutlinedButton(onClick = { gallery.launch(arrayOf("image/*")) }) { Text("🖼 갤러리") }
                }
                if (photos.isNotEmpty()) Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    photos.toList().forEach { p ->
                        Box { Thumb(p, 64.dp); Text("✕", fontSize = 12.sp, color = Color.White, modifier = Modifier.align(Alignment.TopEnd)
                            .background(Color.Black.copy(alpha = .5f), RoundedCornerShape(50)).clickable { photos.remove(p) }.padding(horizontal = 5.dp)) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text(locMsg.ifBlank { if (lat != 0.0 || lon != 0.0) "📍 위치 있음" else "위치 없음" }, fontSize = 12.sp, modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                    TextButton(onClick = { useLoc() }) { Text("지금 위치로", fontSize = 12.sp) }
                }
                TextButton(onClick = { pickDate(ctx, ts.toLocalDate()) { d -> pickTime(ctx, ts.toLocalTime()) { tm -> ts = d.atTime(tm) } } }) {
                    Text("🕒 ${shortDate(ts.toLocalDate())} ${ts.toLocalTime().hhmm()}", fontSize = 13.sp)
                }
                if (!isNew) TextButton(onClick = { askDel = true }) { Text("이 기록 삭제", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = {
            val s = (s0 ?: Spot(tripId = t.id, ts = ts)).copy(kind = kind, name = name.trim(), ts = ts, lat = lat, lon = lon, rating = rating,
                price = price.toLongOrNull() ?: 0, menu = menu.trim(), memo = memo.trim(), again = again, photos = photos.toList(),
                stayType = stayType, nights = nights.toIntOrNull() ?: 0, booking = booking.trim(), transport = transport)
            onSave(s, isNew && addExpense)
        }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
    if (askDel && s0 != null) AlertDialog(onDismissRequest = { askDel = false }, title = { Text("이 기록을 지울까요?") },
        confirmButton = { TextButton(onClick = { askDel = false; s0.photos.forEach { runCatching { File(it).delete() } }; onDelete(s0) }) { Text("삭제", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { askDel = false }) { Text("취소") } })
}

@Composable
private fun ExpenseDialog(t: Trip, e0: Expense?, onDismiss: () -> Unit, onDelete: (Expense) -> Unit, onSave: (Expense) -> Unit) {
    val ctx = LocalContext.current
    var cat by remember { mutableStateOf(e0?.category ?: "식비") }
    var amount by remember { mutableStateOf(e0?.amount?.toString() ?: "") }
    var memo by remember { mutableStateOf(e0?.memo ?: "") }
    var method by remember { mutableStateOf(e0?.method ?: "현금") }
    var ts by remember { mutableStateOf(e0?.ts ?: (if (t.active || t.contains(LocalDate.now())) LocalDateTime.now().withSecond(0).withNano(0) else t.start.atTime(12, 0))) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (e0 == null) "경비 입력" else "경비 고치기") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExpenseCat.all.forEach { c -> FilterChip(cat == c, { cat = c }, { Text("${ExpenseCat.icon(c)} $c") }) }
                }
                OutlinedTextField(amount, { v -> amount = v.filter { it.isDigit() || it == '-' }.take(10) }, label = { Text("금액 (원)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5000L, 10000L, 30000L, 50000L).forEach { v ->
                        AssistChip({ amount = ((amount.toLongOrNull() ?: 0) + v).toString() }, { Text("+${"%,d".format(v)}") })
                    }
                }
                OutlinedTextField(memo, { memo = it }, label = { Text("어디에 (예: 흑돼지, 주유)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if ((e0?.cardTxId ?: -1L) <= 0L) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("현금", "카드", "계좌", "기타").forEach { m -> FilterChip(method == m, { method = m }, { Text(m) }) }
                } else Text("카드 결제에서 불러온 경비예요", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                TextButton(onClick = { pickDate(ctx, ts.toLocalDate()) { d -> pickTime(ctx, ts.toLocalTime()) { tm -> ts = d.atTime(tm) } } }) {
                    Text("🕒 ${shortDate(ts.toLocalDate())} ${ts.toLocalTime().hhmm()}", fontSize = 13.sp)
                }
                if (e0 != null) TextButton(onClick = { onDelete(e0) }) { Text("이 경비 삭제", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = (amount.toLongOrNull() ?: 0) != 0L, onClick = {
            onSave((e0 ?: Expense(tripId = t.id, ts = ts, amount = 0)).copy(category = cat, amount = amount.toLongOrNull() ?: 0, memo = memo.trim(), method = method, ts = ts))
        }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}
