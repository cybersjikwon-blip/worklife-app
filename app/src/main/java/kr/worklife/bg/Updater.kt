package kr.worklife.bg

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases 자동 업데이트
 *  - 공개 저장소: 토큰 없이 동작 / 비공개: 설정에 읽기전용 토큰 입력 (토큰은 폰에만 저장, APK에 포함 안 됨)
 *  - 버전 번호 = 릴리즈 APK 이름 worklife-<번호>.apk (Actions run 번호)
 *  - 받은 APK의 패키지명·버전을 검사한 뒤에만 설치 화면을 띄움
 */
object Updater {
    data class Release(val code: Long, val name: String, val notes: String,
                       val apiUrl: String, val browserUrl: String, val size: Long)

    private const val DEFAULT_REPO = "cybersjikwon-blip/worklife-app"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)

    fun repo(ctx: Context): String = prefs(ctx).getString("repo", DEFAULT_REPO)!!.ifBlank { DEFAULT_REPO }
    fun token(ctx: Context): String = prefs(ctx).getString("token", "")!!
    fun save(ctx: Context, repo: String, token: String) =
        prefs(ctx).edit().putString("repo", repo.trim().removePrefix("https://github.com/").trim('/')).putString("token", token.trim()).apply()

    fun currentCode(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }

    fun currentName(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** 마지막으로 확인된 새 버전 (홈 배너용) */
    fun pending(ctx: Context): Release? {
        val p = prefs(ctx)
        val code = p.getLong("latestCode", 0)
        if (code <= currentCode(ctx)) return null
        return Release(code, p.getString("latestName", "")!!, p.getString("latestNotes", "")!!,
            p.getString("latestApi", "")!!, p.getString("latestBrowser", "")!!, p.getLong("latestSize", 0))
    }

    fun shouldAutoCheck(ctx: Context) = System.currentTimeMillis() - prefs(ctx).getLong("lastCheck", 0) > 6 * 3600_000L

    /** 네트워크 작업 — 반드시 백그라운드 스레드에서 호출. 새 버전이 없으면 null */
    fun check(ctx: Context): Release? {
        val conn = open(URL("https://api.github.com/repos/${repo(ctx)}/releases/latest"), token(ctx), "application/vnd.github+json")
        try {
            when (conn.responseCode) {
                200 -> {}
                401 -> throw IllegalStateException("토큰이 잘못됐거나 만료됐어요")
                403 -> throw IllegalStateException("GitHub 요청 한도 초과 또는 권한 없음 (잠시 후 다시)")
                404 -> throw IllegalStateException(if (token(ctx).isBlank()) "릴리즈를 찾을 수 없어요. 비공개 저장소면 토큰을 입력하세요" else "저장소 이름을 확인하세요 (${repo(ctx)})")
                else -> throw IllegalStateException("GitHub 응답 ${conn.responseCode}")
            }
            val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = j.getJSONArray("assets")
            var best: Release? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.getString("name")
                if (!name.endsWith(".apk")) continue
                val code = Regex("(\\d+)\\.apk$").find(name)?.groupValues?.get(1)?.toLongOrNull()
                    ?: Regex("(\\d+)$").find(j.optString("tag_name"))?.groupValues?.get(1)?.toLongOrNull() ?: continue
                best = Release(code, j.optString("name").ifBlank { j.optString("tag_name") }, j.optString("body"),
                    a.getString("url"), a.getString("browser_download_url"), a.optLong("size"))
            }
            prefs(ctx).edit().putLong("lastCheck", System.currentTimeMillis()).apply()
            val rel = best ?: throw IllegalStateException("최신 릴리즈에 APK가 없어요")
            prefs(ctx).edit().putLong("latestCode", rel.code).putString("latestName", rel.name).putString("latestNotes", rel.notes)
                .putString("latestApi", rel.apiUrl).putString("latestBrowser", rel.browserUrl).putLong("latestSize", rel.size).apply()
            return if (rel.code > currentCode(ctx)) rel else null
        } finally { conn.disconnect() }
    }

    private fun open(url: URL, token: String, accept: String): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "worklife-app")
            // 토큰은 GitHub API에만 보냄 (리다이렉트된 다운로드 서버로는 절대 안 보냄)
            if (token.isNotBlank() && url.host == "api.github.com") setRequestProperty("Authorization", "Bearer $token")
        }

    /** APK 다운로드 → 검증된 파일 반환. 네트워크 작업이므로 백그라운드에서 */
    fun download(ctx: Context, r: Release, progress: (Long, Long) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "worklife-${r.code}.apk")
        val tk = token(ctx)
        var url = URL(if (tk.isNotBlank()) r.apiUrl else r.browserUrl)
        var conn: HttpURLConnection? = null
        for (hop in 0..5) {
            conn = open(url, tk, "application/octet-stream")
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw IllegalStateException("다운로드 주소를 못 찾았어요")
                conn.disconnect(); url = URL(url, loc); conn = null; continue
            }
            if (code != 200) { conn.disconnect(); throw IllegalStateException("다운로드 실패 ($code)") }
            break
        }
        val c = conn ?: throw IllegalStateException("리다이렉트가 너무 많아요")
        try {
            val total = if (c.contentLengthLong > 0) c.contentLengthLong else r.size
            c.inputStream.use { inp ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024); var done = 0L; var lastAt = 0L
                    progress(0, total)
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        o.write(buf, 0, n); done += n
                        val now = System.currentTimeMillis()
                        if (now - lastAt > 120) { lastAt = now; progress(done, total); notifyProgress(ctx, r, done, total) }
                    }
                    progress(done, if (total > 0) total else done)
                }
            }
        } finally { c.disconnect(); clearProgress(ctx) }
        // 검증: 우리 앱인지, 더 새 버전인지
        val info = ctx.packageManager.getPackageArchiveInfo(out.absolutePath, 0)
        if (info == null || info.packageName != ctx.packageName) { out.delete(); throw IllegalStateException("받은 파일이 이 앱의 APK가 아니에요") }
        val newCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        if (newCode <= currentCode(ctx)) { out.delete(); throw IllegalStateException("이미 최신 버전이에요") }
        return out
    }

    /** 알림창 진행 막대 (앱을 내려도 진행 확인) */
    private fun notifyProgress(ctx: Context, r: Release, done: Long, total: Long) {
        runCatching {
            val pct = if (total > 0) (done * 100 / total).toInt() else 0
            val n = androidx.core.app.NotificationCompat.Builder(ctx, Notif.CH_TRACK)
                .setSmallIcon(kr.worklife.R.drawable.ic_stat).setContentTitle("새 버전 ${r.name} 받는 중")
                .setContentText("$pct% · ${mb(done)} / ${mb(total)}").setProgress(100, pct, total <= 0)
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(Notif.openApp(ctx)).build()
            androidx.core.app.NotificationManagerCompat.from(ctx).notify(21, n)
        }
    }
    private fun clearProgress(ctx: Context) { runCatching { androidx.core.app.NotificationManagerCompat.from(ctx).cancel(21) } }
    fun mb(b: Long) = if (b <= 0) "-" else "%.1fMB".format(b / 1_048_576.0)

    /** 설치 화면 띄우기. '이 출처 허용'이 꺼져 있으면 설정 화면으로 보내고 false */
    fun install(ctx: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** 백그라운드 자동 확인 + 새 버전이면 알림 */
    fun autoCheck(ctx: Context, notify: Boolean, done: () -> Unit = {}) {
        if (!shouldAutoCheck(ctx)) { done(); return }
        Thread {
            runCatching { check(ctx) }.getOrNull()?.let { r ->
                if (notify) Notif.post(ctx, 20, "새 버전 ${r.name}이 나왔어요", "앱 > 설정 > 앱 업데이트에서 설치하세요.\n${r.notes.take(200)}")
            }
            done()
        }.start()
    }
}
