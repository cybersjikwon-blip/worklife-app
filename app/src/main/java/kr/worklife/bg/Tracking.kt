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
    const val ACT_POLL = "kr.worklife.POLL"

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

    /** 학습된 퇴근 시간대 (자동 퇴근 5회 이상 쌓이면) */
    fun learned(ctx: Context, c: Company): LearnedWindow? =
        runCatching { learnCheckoutWindow(c, Repo.get(ctx).recentAutoCheckouts(c.id)) }.getOrNull()

    /** 다음 근무일 감시 시작(정시-5분, 학습상 더 일찍 나가면 앞당김) + 매일 00:10 마감 + 지금 감시 중이면 5분 확인 알람 */
    fun scheduleAll(ctx: Context) {
        val repo = Repo.get(ctx)
        val now = LocalDateTime.now()
        val next = (0L..21L).asSequence().map { now.toLocalDate().plusDays(it) }.mapNotNull { d ->
            val c = repo.companyOn(d) ?: return@mapNotNull null
            if (!c.hasLocation || !HolidayCalendar(c).isWorkday(d)) return@mapNotNull null
            d.atTime(trackingStart(c, learned(ctx, c))).takeIf { it.isAfter(now) }
        }.firstOrNull()
        if (next != null) set(ctx, next, pi(ctx, ACT_TRACK, 1))
        set(ctx, now.toLocalDate().plusDays(1).atTime(0, 10), pi(ctx, ACT_SETTLE, 2))
        if (shouldTrackNow(ctx)) schedulePoll(ctx, 5)
    }

    fun schedulePoll(ctx: Context, minutes: Int) = set(ctx, LocalDateTime.now().plusMinutes(minutes.toLong()), pi(ctx, ACT_POLL, 3))
    fun cancelPoll(ctx: Context) = runCatching { ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, ACT_POLL, 3)) }

    /**
     * 지금 위치를 감시해야 하나?
     *  - 근무일: 감시 시작시각 ~ 하루 마감, 아직 퇴근 기록 없음
     *  - 휴일: 오늘 회사 반경에 들어온 적 있고 아직 나간 게 확정 안 됨
     */
    fun shouldTrackNow(ctx: Context): Boolean {
        val repo = Repo.get(ctx)
        val now = LocalDateTime.now()
        val d = now.toLocalDate()
        val c = repo.companyOn(d) ?: return false
        if (!c.hasLocation) return false
        val t = now.toLocalTime()
        if (!t.isBefore(hm(repo.settings.dayCutoff))) return false
        if (!HolidayCalendar(c).isWorkday(d)) {
            if (repo.day(d, c.id) != null) return false
            val g = Engine.evaluate(ctx, d).tracker ?: return false
            return g.firstInside != null && g.checkout == null
        }
        val r = repo.day(d, c.id)
        if (r != null && (r.status != Status.WORK || r.checkOut != null)) return false
        return !t.isBefore(trackingStart(c, learned(ctx, c)))
    }

    /** 지금 위치 확인 간격(분): 학습된 퇴근 시간대면 1분, 그 외 5분 */
    fun intervalNow(ctx: Context): Int {
        val repo = Repo.get(ctx)
        val d = java.time.LocalDate.now()
        val c = repo.companyOn(d) ?: return 5
        if (!HolidayCalendar(c).isWorkday(d)) return 2
        return pollIntervalMin(java.time.LocalTime.now(), learned(ctx, c))
    }

    fun startTracking(ctx: Context) {
        if (!ctx.hasFineLoc()) { Notif.post(ctx, 12, "위치 권한이 필요해요", "퇴근 자동 감지를 하려면 앱에서 위치 권한을 허용해 주세요."); return }
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, TrackingService::class.java))
        } catch (e: Exception) {
            // 백그라운드 시작 제한 등 → 5분 확인 알람과 지오펜스가 백업
            Notif.post(ctx, 13, "퇴근 감지를 시작하지 못했어요", "5분마다 위치 확인으로 대신해요. 앱을 한 번 열면 정상으로 돌아와요.")
        }
    }
}

/** 위치 1건 처리 공통 경로 (서비스·알람·지오펜스·앱 실행 모두 이걸 사용). 퇴근 확정되면 true */
object Tracker {
    fun onSample(ctx: Context, l: Location): Boolean {
        val repo = Repo.get(ctx)
        val smp = l.toSample()
        repo.addLoc(smp)
        val d = smp.ts.toLocalDate()
        val rec = Engine.checkToday(ctx)
        if (rec?.checkOut != null) {
            if (rec.source != Source.MANUAL) notifyOnce(ctx, rec)
            return true
        }
        val g = Engine.checkHoliday(ctx)
        if (g?.checkout != null) return true
        val c = repo.companyOn(d) ?: return true
        return !c.hasLocation
    }

    private fun notifyOnce(ctx: Context, rec: DayRecord) {
        val p = ctx.getSharedPreferences("worklife", Context.MODE_PRIVATE)
        val key = "checkoutNotified-${rec.day}"
        val v = rec.checkOut?.hhmm() ?: return
        if (p.getString(key, null) == v) return
        p.edit().putString(key, v).apply()
        val repo = Repo.get(ctx)
        repo.companies.firstOrNull { it.id == rec.companyId }?.let { Notif.checkout(ctx, rec, it, repo.settings) }
    }

    /** 앱을 열었을 때 등: 즉시 1회 위치 확인 */
    fun oneShot(ctx: Context, done: () -> Unit = {}) {
        if (!Scheduler.shouldTrackNow(ctx)) { done(); return }
        Geo.current(ctx) { l -> if (l != null) runCatching { onSample(ctx, l) }; done() }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Notif.init(ctx)
        when (intent.action) {
            Scheduler.ACT_TRACK -> if (Scheduler.shouldTrackNow(ctx)) { Scheduler.startTracking(ctx); Scheduler.schedulePoll(ctx, 5) }
            Scheduler.ACT_POLL -> {
                if (!Scheduler.shouldTrackNow(ctx)) { Scheduler.cancelPoll(ctx); return }
                // 서비스가 죽었어도 5분마다 직접 위치를 잡아 판정 + 서비스 재시작
                Scheduler.startTracking(ctx)
                val pr = goAsync()
                val h = Handler(Looper.getMainLooper())
                var finished = false
                fun finish() { if (!finished) { finished = true; runCatching { pr.finish() } } }
                h.postDelayed({ finish() }, 25_000)
                Geo.current(ctx) { l ->
                    val done = l != null && runCatching { Tracker.onSample(ctx, l) }.getOrDefault(false)
                    if (!done && Scheduler.shouldTrackNow(ctx)) Scheduler.schedulePoll(ctx, 5) else Scheduler.cancelPoll(ctx)
                    finish()
                }
                return
            }
            Scheduler.ACT_SETTLE -> {
                Engine.settlePast(ctx)
                runCatching { Engine.paydayNotice(ctx) }
                val pr = goAsync()
                Updater.autoCheck(ctx, notify = true) { pr.finish() }
            }
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

/** 지오펜스: 서비스가 못 떠도 OS가 출입 이벤트를 준다. 들어오면(휴일 포함) 감시 시작, 나가면 2분 뒤 재확인 */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val ev = GeofencingEvent.fromIntent(intent) ?: return
        if (ev.hasError()) return
        val loc = ev.triggeringLocation ?: return
        Notif.init(ctx)
        val done = runCatching { Tracker.onSample(ctx, loc) }.getOrDefault(false)
        if (done) { Scheduler.cancelPoll(ctx); return }
        if (Scheduler.shouldTrackNow(ctx)) {
            Scheduler.startTracking(ctx)
            Scheduler.schedulePoll(ctx, if (ev.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) 2 else 5)
        }
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
    private var interval = -1
    private var lastFix = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) { r.locations.forEach { onLoc(it) } }
    }
    private val listener = LocationListener { onLoc(it) }

    /** 1분마다: 감시 계속할지, 확인 간격(학습 시간대 1분/그 외 5분)이 바뀌었는지, 위치가 끊겼는지 점검 */
    private val watchdog = object : Runnable {
        override fun run() {
            val ctx = this@TrackingService
            if (!Scheduler.shouldTrackNow(ctx)) { stopSelf(); return }
            val want = Scheduler.intervalNow(ctx)
            if (want != interval) startUpdates(want)
            // 위치가 간격의 2배 넘게 안 들어오면 직접 1회 요청
            if (System.currentTimeMillis() - lastFix > want * 2 * 60_000L) {
                Geo.current(ctx) { l -> if (l != null) onLoc(l) }
            }
            handler.postDelayed(this, 60_000L)
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
        if (interval < 0) {
            lastFix = System.currentTimeMillis()
            startUpdates(Scheduler.intervalNow(this))
        }
        Scheduler.schedulePoll(this, 5) // 서비스가 죽어도 5분 뒤 알람이 이어받음
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 60_000L)
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates(minutes: Int) {
        if (!hasFineLoc()) { stopSelf(); return }
        interval = minutes
        val ms = minutes * 60_000L
        runCatching { fused?.removeLocationUpdates(callback) }
        runCatching { lm?.removeUpdates(listener) }
        try {
            fused = LocationServices.getFusedLocationProviderClient(this).also {
                val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, ms)
                    .setMinUpdateIntervalMillis(ms / 2).build()
                it.requestLocationUpdates(req, callback, Looper.getMainLooper())
            }
        } catch (_: Exception) {
            fused = null
        }
        // 구글 위치 서비스 이상 대비: 기기 GPS도 같이 받는다 (중복 샘플은 판정에 영향 없음)
        try {
            lm = getSystemService(LocationManager::class.java).also {
                if (it.isProviderEnabled(LocationManager.GPS_PROVIDER))
                    it.requestLocationUpdates(LocationManager.GPS_PROVIDER, ms, 0f, listener, Looper.getMainLooper())
                if (fused == null && it.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                    it.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, ms, 0f, listener, Looper.getMainLooper())
            }
        } catch (_: Exception) { }
    }

    private fun onLoc(l: Location) {
        lastFix = System.currentTimeMillis()
        val done = runCatching { Tracker.onSample(this, l) }.getOrDefault(false)
        if (done) { Scheduler.cancelPoll(this); stopSelf(); return }
        val c = Repo.get(this).currentCompany()
        val dist = haversineM(c.lat, c.lon, l.latitude, l.longitude).toInt()
        val hol = !HolidayCalendar(c).isWorkday(java.time.LocalDate.now())
        getSystemService(NotificationManager::class.java).notify(1, Notif.tracking(this,
            (if (hol) "휴일 · " else "") + "${c.name}에서 ${dist}m · ${interval}분마다 확인 · ${java.time.LocalTime.now().hhmm()}"))
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { fused?.removeLocationUpdates(callback) }
        runCatching { lm?.removeUpdates(listener) }
        super.onDestroy()
    }
}
