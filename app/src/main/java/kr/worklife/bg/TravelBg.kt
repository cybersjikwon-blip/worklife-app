package kr.worklife.bg

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kr.worklife.R
import kr.worklife.core.*
import kr.worklife.data.Repo
import kr.worklife.data.RepoDump
import kr.worklife.ui.MainActivity
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 여행 중 모드
 *  - 알림창 고정 알림: [기록] [경비] 바로가기
 *  - 자동 경로: 정해진 간격(기본 30분)마다 위치 1번 (100m 이상 움직였거나 2시간 지났을 때만 저장)
 *  - 하루 정리: 21:00 "오늘 ○곳 · 경비 ○원"
 *  - 1년 전 오늘: 매일 09:00 지난 여행 알림
 */
object TravelMode {
    const val ACT_TRACK = "kr.worklife.TRAVEL_TRACK"
    const val ACT_DAY = "kr.worklife.TRAVEL_DAY"
    const val ACT_MEMORY = "kr.worklife.TRAVEL_MEMORY"
    private const val NOTI_ID = 60

    fun start(ctx: Context, t: Trip): Long {
        val repo = Repo.get(ctx)
        repo.trips().filter { it.active && it.id != t.id }.forEach { repo.saveTrip(it.copy(active = false, end = it.end ?: LocalDate.now())) }
        val id = repo.saveTrip(t.copy(active = true, end = null))
        showOngoing(ctx)
        scheduleAll(ctx)
        trackNow(ctx) {}
        return id
    }

    fun end(ctx: Context, t: Trip) {
        val repo = Repo.get(ctx)
        val lastDay = (repo.spots(t.id).map { it.ts.toLocalDate() } + repo.expenses(t.id).map { it.ts.toLocalDate() } + LocalDate.now()).filter { !it.isBefore(t.start) }.max()
        repo.saveTrip(t.copy(active = false, end = t.end ?: lastDay))
        NotificationManagerCompat.from(ctx).cancel(NOTI_ID)
        scheduleAll(ctx)
    }

    private fun open(ctx: Context, code: Int, action: String) = PendingIntent.getActivity(ctx, code,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("openTab", 4).putExtra("openApp", "travel").putExtra("travelAction", action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    @SuppressLint("MissingPermission")
    fun showOngoing(ctx: Context) {
        val t = Repo.get(ctx).activeTrip() ?: return
        Notif.init(ctx)
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val repo = Repo.get(ctx)
        val today = LocalDate.now()
        val n = repo.spots(t.id).count { it.ts.toLocalDate() == today }
        val spend = repo.expenses(t.id).filter { it.ts.toLocalDate() == today }.sumOf { it.amount }
        try {
            nm.notify(NOTI_ID, NotificationCompat.Builder(ctx, Notif.CH_TRACK).setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("✈️ ${t.title} · 여행 중")
                .setContentText("오늘 ${n}곳 · 경비 ${"%,d".format(spend)}원 — 눌러서 기록")
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
                .setContentIntent(open(ctx, 61, "home"))
                .addAction(0, "📍 기록", open(ctx, 62, "record"))
                .addAction(0, "💸 경비", open(ctx, 63, "expense"))
                .addAction(0, "📷 사진", open(ctx, 64, "photo")).build())
        } catch (_: SecurityException) { }
    }

    /** 알람 다시 걸기 (앱 열 때·부팅·알람 때마다) */
    fun scheduleAll(ctx: Context) {
        val repo = Repo.get(ctx)
        val now = LocalDateTime.now()
        val active = repo.activeTrip()
        if (active != null) {
            val m = repo.travelTrackMin
            if (m > 0) Scheduler.set(ctx, now.plusMinutes(m.toLong()), Scheduler.pi(ctx, ACT_TRACK, 70))
            if (repo.travelDailyNote) {
                val t = now.toLocalDate().atTime(21, 0).let { if (it.isAfter(now)) it else it.plusDays(1) }
                Scheduler.set(ctx, t, Scheduler.pi(ctx, ACT_DAY, 71))
            }
        }
        if (repo.travelMemory && repo.trips().isNotEmpty()) {
            val t = now.toLocalDate().atTime(9, 0).let { if (it.isAfter(now)) it else it.plusDays(1) }
            Scheduler.set(ctx, t, Scheduler.pi(ctx, ACT_MEMORY, 72))
        }
    }

    fun onAlarm(ctx: Context, action: String, done: () -> Unit) {
        Notif.init(ctx)
        val repo = Repo.get(ctx)
        when (action) {
            ACT_TRACK -> { trackNow(ctx) { showOngoing(ctx); scheduleAll(ctx); done() }; return }
            ACT_DAY -> runCatching {
                val t = repo.activeTrip() ?: return@runCatching
                val today = LocalDate.now()
                val sp = repo.spots(t.id).filter { it.ts.toLocalDate() == today }
                val spend = repo.expenses(t.id).filter { it.ts.toLocalDate() == today }.sumOf { it.amount }
                val km = Travel.routeKm(repo.track(t.id).filter { it.ts.toLocalDate() == today })
                val noRating = sp.count { it.rating <= 0 }
                Notif.post(ctx, 65, "✈️ 오늘 ${sp.size}곳 · ${"%.0f".format(km)}km · 경비 ${"%,d".format(spend)}원",
                    if (noRating > 0) "별점 안 남긴 곳 ${noRating}곳 · 눌러서 오늘 정리하기" else "오늘도 즐거운 여행이었어요 🌙")
            }
            ACT_MEMORY -> runCatching {
                val today = LocalDate.now()
                val p = ctx.getSharedPreferences("worklife", Context.MODE_PRIVATE)
                if (p.getString("memoryShown", "") == today.toString()) return@runCatching
                val hits = Travel.onThisDay(repo.trips(), today)
                if (hits.isNotEmpty()) {
                    p.edit().putString("memoryShown", today.toString()).apply()
                    val (t, y) = hits.first()
                    val n = repo.spots(t.id).size
                    Notif.post(ctx, 66, "📅 ${y}년 전 오늘, ${t.title}", "${t.periodText} · 기록 ${n}곳" + (if (hits.size > 1) " 외 ${hits.size - 1}개 여행" else "") + " — 눌러서 추억 보기")
                }
            }
        }
        scheduleAll(ctx)
        done()
    }

    /** 자동 경로 1번 */
    fun trackNow(ctx: Context, done: () -> Unit) {
        val repo = Repo.get(ctx)
        val t = repo.activeTrip() ?: run { done(); return }
        Geo.current(ctx) { l ->
            runCatching {
                if (l != null && l.accuracy <= 200) {
                    val now = LocalDateTime.now()
                    if (Travel.shouldTrack(repo.track(t.id).lastOrNull(), l.latitude, l.longitude, now))
                        repo.addTrack(TrackPoint(t.id, now, l.latitude, l.longitude))
                }
            }
            done()
        }
    }
}

/** 여행 사진: 앱 폴더로 줄여서 복사(갤러리에서 지워도 남음) + 찍은 날짜·위치 읽기 */
object TravelPhotos {
    private val EXIF_FMT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    fun dir(ctx: Context, tripId: Long) = File(ctx.filesDir, "travel/$tripId").apply { mkdirs() }

    /** 카메라 촬영용 빈 파일 */
    fun newFile(ctx: Context, tripId: Long) = File(dir(ctx, tripId), "p${System.currentTimeMillis()}.jpg")

    /** 사진 가져오기 → (저장 경로, 찍은 시각, 위도, 경도). 실패한 사진은 건너뜀 */
    fun import(ctx: Context, tripId: Long, uris: List<Uri>): List<Travel.PhotoInfo> = uris.mapNotNull { u ->
        runCatching {
            val (ts, la, lo, rot) = readExif(ctx, u)
            val out = newFile(ctx, tripId)
            saveScaled(ctx, u, out, rot)
            Travel.PhotoInfo(out.absolutePath, ts, la, lo)
        }.getOrNull()
    }

    /** 카메라로 찍은 파일 다듬기 (크기 줄이기·회전) → 정보 */
    fun fromCameraFile(ctx: Context, f: File): Travel.PhotoInfo? = runCatching {
        val u = Uri.fromFile(f)
        val (ts, la, lo, rot) = readExif(ctx, u)
        val tmp = File(f.parentFile, f.name + ".tmp")
        saveScaled(ctx, u, tmp, rot)
        f.delete(); tmp.renameTo(f)
        Travel.PhotoInfo(f.absolutePath, ts ?: LocalDateTime.now(), la, lo)
    }.getOrNull()

    private data class Ex(val ts: LocalDateTime?, val lat: Double, val lon: Double, val rot: Int)

    private fun readExif(ctx: Context, u: Uri): Ex = runCatching {
        ctx.contentResolver.openInputStream(u)!!.use { ins ->
            val e = ExifInterface(ins)
            val ts = (e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: e.getAttribute(ExifInterface.TAG_DATETIME))
                ?.let { runCatching { LocalDateTime.parse(it.trim(), EXIF_FMT) }.getOrNull() }
            val ll = FloatArray(2)
            val has = e.getLatLong(ll)
            val rot = when (e.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90; ExifInterface.ORIENTATION_ROTATE_180 -> 180; ExifInterface.ORIENTATION_ROTATE_270 -> 270; else -> 0
            }
            Ex(ts, if (has) ll[0].toDouble() else 0.0, if (has) ll[1].toDouble() else 0.0, rot)
        }
    }.getOrDefault(Ex(null, 0.0, 0.0, 0))

    /** 긴 변 1600px, JPEG 85% (장당 약 300KB) */
    private fun saveScaled(ctx: Context, u: Uri, out: File, rot: Int) {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(u)!!.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 1600) sample *= 2
        val bmp0 = ctx.contentResolver.openInputStream(u)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }!!
        val scale = 1600f / maxOf(bmp0.width, bmp0.height)
        val m = Matrix().apply { if (scale < 1f) postScale(scale, scale); if (rot != 0) postRotate(rot.toFloat()) }
        val bmp = if (scale < 1f || rot != 0) Bitmap.createBitmap(bmp0, 0, 0, bmp0.width, bmp0.height, m, true) else bmp0
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (bmp !== bmp0) bmp0.recycle()
    }
}

/**
 * 내보내기 백업: 기록(전체 표) + 설정 + 여행 사진을 zip 하나로 → 원하는 곳(드라이브·카톡·PC)에 저장
 * 가져오기: 같은 zip으로 복원 (폰 바꿀 때)
 */
object Backup {
    fun export(ctx: Context, dest: Uri): String = runCatching {
        val repo = Repo.get(ctx)
        var photos = 0
        ctx.contentResolver.openOutputStream(dest)!!.use { os ->
            ZipOutputStream(os.buffered()).use { z ->
                val meta = JSONObject().put("app", "worklife").put("version", 1).put("at", LocalDateTime.now().withNano(0).toString())
                z.putNextEntry(ZipEntry("meta.json")); z.write(meta.toString().toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("prefs.json")); z.write(RepoDump.prefsToJson(ctx).toString().toByteArray()); z.closeEntry()
                for (t in RepoDump.TABLES) {
                    val a = runCatching { RepoDump.tableToJson(repo, t) }.getOrNull() ?: continue
                    z.putNextEntry(ZipEntry("db/$t.json")); z.write(a.toString().toByteArray()); z.closeEntry()
                }
                File(ctx.filesDir, "travel").walkTopDown().filter { it.isFile }.forEach { f ->
                    z.putNextEntry(ZipEntry("travel/" + f.relativeTo(File(ctx.filesDir, "travel")).path)); f.inputStream().use { it.copyTo(z) }; z.closeEntry(); photos++
                }
            }
        }
        "백업 완료 · 사진 ${photos}장 포함"
    }.getOrElse { "백업 실패: ${it.message ?: it.javaClass.simpleName}" }

    fun restore(ctx: Context, src: Uri): String = runCatching {
        val repo = Repo.get(ctx)
        var tables = 0; var photos = 0; var ok = false
        ctx.contentResolver.openInputStream(src)!!.use { ins ->
            ZipInputStream(ins.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    when {
                        e.name == "meta.json" -> ok = String(z.readBytes()).contains("worklife")
                        e.name == "prefs.json" -> RepoDump.jsonToPrefs(ctx, JSONObject(String(z.readBytes())))
                        e.name.startsWith("db/") && e.name.endsWith(".json") -> {
                            val t = e.name.removePrefix("db/").removeSuffix(".json")
                            if (t in RepoDump.TABLES) { RepoDump.jsonToTable(repo, t, org.json.JSONArray(String(z.readBytes()))); tables++ }
                        }
                        e.name.startsWith("travel/") && !e.isDirectory && !e.name.contains("..") -> {
                            val f = File(ctx.filesDir, e.name); f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) }; photos++
                        }
                    }
                }
            }
        }
        if (!ok && tables == 0) "이 앱의 백업 파일이 아니에요" else "복원 완료 · 기록 ${tables}종 · 사진 ${photos}장"
    }.getOrElse { "복원 실패: ${it.message ?: it.javaClass.simpleName}" }
}
