package kr.worklife.bg

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import kr.worklife.R
import kr.worklife.core.*
import kr.worklife.data.Repo
import kr.worklife.ui.MainActivity
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

fun Context.hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
fun Context.hasFineLoc() = hasPerm(Manifest.permission.ACCESS_FINE_LOCATION) || hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION)
fun Context.hasBgLoc() = hasPerm(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

fun Location.toSample() = LocSample(
    LocalDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneId.systemDefault()).withNano(0),
    latitude, longitude, if (hasAccuracy()) accuracy.toDouble() else 30.0)

// ───────────────────────── 알림 ─────────────────────────
object Notif {
    const val CH_TRACK = "track"
    const val CH_EVENT = "event"

    fun init(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_TRACK, "퇴근 감지 중", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_EVENT, "퇴근 기록·확인 요청", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun tracking(ctx: Context, text: String) = NotificationCompat.Builder(ctx, CH_TRACK)
        .setSmallIcon(R.drawable.ic_stat).setContentTitle("퇴근 감지 중").setContentText(text)
        .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(openApp(ctx)).build()

    @SuppressLint("MissingPermission")
    fun post(ctx: Context, id: Int, title: String, text: String) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        try {
            nm.notify(id, NotificationCompat.Builder(ctx, CH_EVENT).setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true).setContentIntent(openApp(ctx)).build())
        } catch (_: SecurityException) { }
    }

    fun checkout(ctx: Context, r: DayRecord, c: Company, s: AppSettings) {
        val ot = overtimeMin(c, r)
        val pay = (ot / 60.0 * s.hourly(c, r.day.year) * c.overtimeRate).toInt()
        val t = r.checkOut?.hhmm() ?: "-"
        if (r.source == Source.NEEDS_CHECK) post(ctx, 11, "퇴근 확인이 필요해요", "$t 로 임시 기록했어요. 앱에서 확인해 주세요. ${r.note}")
        else post(ctx, 10, "퇴근 $t 기록", if (ot > 0) "오늘 연장 ${fmtHm(ot)} (+${"%,d".format(pay)}원)" else "오늘은 정시 퇴근이에요. 수고하셨습니다!")
    }
}

// ───────────────────────── 예약 ─────────────────────────
object Scheduler {
    const val ACT_TRACK = "kr.worklife.TRACK"
    const val ACT_SETTLE = "kr.worklife.SETTLE"

    private fun pi(ctx: Context, act: String, code: Int) = PendingIntent.getBroadcast(ctx, code,
        Intent(ctx, AlarmReceiver::class.java).setAction(act), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun set(ctx: Context, at: LocalDateTime, p: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, p)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, p)
        }
    }

    /** 다음 근무일 (정시-5분) 감지 시작 + 매일 00:10 하루 마감 */
    fun scheduleAll(ctx: Context) {
        val repo = Repo.get(ctx)
        val now = LocalDateTime.now()
        val next = (0L..21L).asSequence().map { now.toLocalDate().plusDays(it) }.mapNotNull { d ->
            val c = repo.companyOn(d) ?: return@mapNotNull null
            if (!c.hasLocation || !HolidayCalendar(c).isWorkday(d)) return@mapNotNull null
            d.atTime(hm(c.workEnd)).minusMinutes(5).takeIf { it.isAfter(now) }
        }.firstOrNull()
        if (next != null) set(ctx, next, pi(ctx, ACT_TRACK, 1))
        set(ctx, now.toLocalDate().plusDays(1).atTime(0, 10), pi(ctx, ACT_SETTLE, 2))
    }

    /** 지금 감지해야 하는 시간인가? (근무일, 정시-5분 ~ 마감, 아직 퇴근 기록 없음) */
    fun shouldTrackNow(ctx: Context): Boolean {
        val repo = Repo.get(ctx)
        val now = LocalDateTime.now()
        val d = now.toLocalDate()
        val c = repo.companyOn(d) ?: return false
        if (!c.hasLocation || !HolidayCalendar(c).isWorkday(d)) return false
        val r = repo.day(d, c.id)
        if (r != null && (r.status != Status.WORK || r.checkOut != null)) return false
        val t = now.toLocalTime()
        return !t.isBefore(hm(c.workEnd).minusMinutes(5)) && t.isBefore(hm(repo.settings.dayCutoff))
    }

    fun startTracking(ctx: Context) {
        if (!ctx.hasFineLoc()) { Notif.post(ctx, 12, "위치 권한이 필요해요", "퇴근 자동 감지를 하려면 앱에서 위치 권한을 허용해 주세요."); return }
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, TrackingService::class.java))
        } catch (e: Exception) {
            // 안드로이드 12+ 백그라운드 시작 제한 등 → 지오펜스가 백업, 사용자에게 안내
            Notif.post(ctx, 13, "퇴근 감지를 시작하지 못했어요", "탭해서 앱을 한 번 열어주세요. (정확한 알람·배터리 예외 설정을 확인하세요)")
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Notif.init(ctx)
        when (intent.action) {
            Scheduler.ACT_TRACK -> if (Scheduler.shouldTrackNow(ctx)) Scheduler.startTracking(ctx)
            Scheduler.ACT_SETTLE -> Engine.settlePast(ctx)
        }
        Scheduler.scheduleAll(ctx)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Notif.init(ctx)
        Scheduler.scheduleAll(ctx)
        Geo.register(ctx)
        if (Scheduler.shouldTrackNow(ctx)) Scheduler.startTracking(ctx)
    }
}

/** 지오펜스(백업 경로): 서비스가 못 떠도 OS가 출입 이벤트를 준다 */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val ev = GeofencingEvent.fromIntent(intent) ?: return
        if (ev.hasError()) return
        val loc = ev.triggeringLocation ?: return
        Notif.init(ctx)
        val repo = Repo.get(ctx)
        repo.addLoc(loc.toSample())
        val c = repo.companyOn(loc.toSample().ts.toLocalDate()) ?: return
        val rec = Engine.checkToday(ctx)
        if (rec?.checkOut != null && rec.source != Source.MANUAL) Notif.checkout(ctx, rec, c, repo.settings)
        else if (ev.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT && Scheduler.shouldTrackNow(ctx)) Scheduler.startTracking(ctx)
    }
}

object Geo {
    @SuppressLint("MissingPermission")
    fun register(ctx: Context) {
        val c = Repo.get(ctx).currentCompany()
        if (!c.hasLocation || !ctx.hasFineLoc() || !ctx.hasBgLoc()) return
        val pi = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, GeofenceReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val fence = Geofence.Builder().setRequestId("work-${c.id}")
            .setCircularRegion(c.lat, c.lon, maxOf(100f, c.radiusM.toFloat()))
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT).build()
        val req = GeofencingRequest.Builder().setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER).addGeofence(fence).build()
        try {
            val client = LocationServices.getGeofencingClient(ctx)
            client.removeGeofences(pi).addOnCompleteListener {
                try { client.addGeofences(req, pi) } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }

    /** 현재 위치 1회: 정밀 → 마지막 위치 → 기기 LocationManager 순으로 시도 */
    @SuppressLint("MissingPermission")
    fun current(ctx: Context, cb: (Location?) -> Unit) {
        if (!ctx.hasFineLoc()) { cb(null); return }
        fun fallback() {
            val lm = ctx.getSystemService(LocationManager::class.java)
            val best = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
            cb(best)
        }
        try {
            val f = LocationServices.getFusedLocationProviderClient(ctx)
            f.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .addOnSuccessListener { l ->
                    if (l != null) cb(l) else f.lastLocation.addOnSuccessListener { l2 -> if (l2 != null) cb(l2) else fallback() }
                        .addOnFailureListener { fallback() }
                }
                .addOnFailureListener { fallback() }
        } catch (_: Exception) { fallback() }
    }
}

// ───────────────────────── 포그라운드 서비스 ─────────────────────────
class TrackingService : Service() {
    private var fused: FusedLocationProviderClient? = null
    private var lm: LocationManager? = null
    private val handler = Handler(Looper.getMainLooper())

    private val callback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) { r.locations.forEach { onLoc(it) } }
    }
    private val listener = LocationListener { onLoc(it) }
    private val watchdog = object : Runnable {
        override fun run() {
            val rec = Engine.checkToday(this@TrackingService)
            if (rec?.checkOut != null || !Scheduler.shouldTrackNow(this@TrackingService)) { finishWith(rec); return }
            handler.postDelayed(this, 10 * 60_000L)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notif.init(this)
        val c = Repo.get(this).currentCompany()
        try {
            ServiceCompat.startForeground(this, 1, Notif.tracking(this, "${c.name} · 반경 ${c.radiusM.toInt()}m 벗어나면 퇴근 기록"),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } catch (e: Exception) {
            stopSelf(); return START_NOT_STICKY
        }
        if (!Scheduler.shouldTrackNow(this)) { stopSelf(); return START_NOT_STICKY }
        startUpdates()
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 10 * 60_000L)
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        if (!hasFineLoc()) { stopSelf(); return }
        try {
            fused = LocationServices.getFusedLocationProviderClient(this).also {
                val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 60_000L)
                    .setMinUpdateIntervalMillis(30_000L).build()
                it.requestLocationUpdates(req, callback, Looper.getMainLooper())
            }
        } catch (_: Exception) {
            fused = null
        }
        // 구글 위치 서비스 이상 대비: 기기 GPS도 같이 받는다 (중복 샘플은 판정에 영향 없음)
        try {
            lm = getSystemService(LocationManager::class.java).also {
                if (it.isProviderEnabled(LocationManager.GPS_PROVIDER))
                    it.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60_000L, 0f, listener, Looper.getMainLooper())
                if (fused == null && it.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                    it.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60_000L, 0f, listener, Looper.getMainLooper())
            }
        } catch (_: Exception) { }
    }

    private fun onLoc(l: Location) {
        val repo = Repo.get(this)
        repo.addLoc(l.toSample())
        val rec = Engine.checkToday(this)
        if (rec?.checkOut != null) { finishWith(rec); return }
        val c = repo.currentCompany()
        val dist = haversineM(c.lat, c.lon, l.latitude, l.longitude).toInt()
        getSystemService(NotificationManager::class.java)
            .notify(1, Notif.tracking(this, "${c.name}에서 ${dist}m · 오차 ${l.accuracy.toInt()}m"))
    }

    private fun finishWith(rec: DayRecord?) {
        val repo = Repo.get(this)
        if (rec?.checkOut != null && rec.source != Source.MANUAL) {
            repo.companies.firstOrNull { it.id == rec.companyId }?.let { Notif.checkout(this, rec, it, repo.settings) }
        }
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { fused?.removeLocationUpdates(callback) }
        runCatching { lm?.removeUpdates(listener) }
        super.onDestroy()
    }
}
