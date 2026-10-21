package kr.worklife.bg

import android.Manifest
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kr.worklife.core.CardParser
import kr.worklife.data.Repo
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 카드 승인 문자·알림 → 사용 내역 저장 (세 경로 모두 같은 함수를 거쳐 중복 없이) */
object CardIngest {
    fun hasSms(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    fun hasNotiAccess(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun ingest(ctx: Context, sender: String, body: String, at: LocalDateTime, source: String): Boolean {
        val repo = Repo.get(ctx)
        val tx = CardParser.parse(sender, body, at, repo.cardKeywords, source) ?: return false
        return repo.addCardTx(tx)
    }

    /**
     * 문자함에서 불러오기: from 날짜부터 (이미 읽은 문자는 중복으로 걸러짐)
     * @return (읽은 문자 수, 새로 추가된 결제 수)
     */
    fun scanInbox(ctx: Context, from: LocalDate): Pair<Int, Int> {
        if (!hasSms(ctx)) return 0 to 0
        val repo = Repo.get(ctx)
        val fromMs = from.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        var read = 0; var added = 0; var maxMs = repo.cardSmsScannedUntil
        runCatching {
            ctx.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("address", "body", "date"),
                "date >= ?", arrayOf(fromMs.toString()), "date ASC")?.use { c ->
                while (c.moveToNext()) {
                    read++
                    val ms = c.getLong(2)
                    val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
                    if (runCatching { ingest(ctx, c.getString(0) ?: "", c.getString(1) ?: "", at, "sms") }.getOrDefault(false)) added++
                    if (ms > maxMs) maxMs = ms
                }
            }
        }
        repo.cardSmsScannedUntil = maxMs
        return read to added
    }

    /** 앱을 열 때: 마지막으로 읽은 뒤에 온 문자만 이어서 (수신 알림을 놓쳤을 때 대비) */
    fun catchUp(ctx: Context) {
        if (!hasSms(ctx)) return
        val repo = Repo.get(ctx)
        val last = repo.cardSmsScannedUntil
        val (start, _) = kr.worklife.core.cardRange(repo.cardPeriod, LocalDate.now())
        val from = if (last > 0) LocalDateTime.ofInstant(Instant.ofEpochMilli(last), ZoneId.systemDefault()).toLocalDate().minusDays(1) else start
        scanInbox(ctx, from)
    }
}

/** 문자 수신 즉시 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull() ?: return
        // 긴 문자는 여러 조각으로 옴 → 보낸 번호별로 이어 붙임
        msgs.groupBy { it.originatingAddress ?: "" }.forEach { (from, parts) ->
            val body = parts.joinToString("") { it.messageBody ?: "" }
            val ms = parts.minOf { it.timestampMillis }
            val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(if (ms > 0) ms else System.currentTimeMillis()), ZoneId.systemDefault())
            if (runCatching { CardIngest.ingest(ctx, from, body, at, "sms") }.getOrDefault(false)) runCatching { Widgets.updateAll(ctx) }
        }
        runCatching { Repo.get(ctx).cardSmsScannedUntil = maxOf(Repo.get(ctx).cardSmsScannedUntil, System.currentTimeMillis()) }
    }
}

/** 카카오 알림톡·페이북·BC 앱 알림 (요즘은 문자 대신 알림톡으로 오는 경우가 많음) */
class CardNotifService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val ex = sbn.notification?.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        val body = "$title\n$text"
        // 빠른 거르기: 금액·승인/취소가 없으면 무시 (다른 앱 알림은 저장하지 않음)
        if (!body.contains("원") && !body.contains("USD")) return
        if (!body.contains("승인") && !body.contains("취소") && !body.contains("결제")) return
        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(sbn.postTime), ZoneId.systemDefault())
        if (runCatching { CardIngest.ingest(this, "", body, at, "noti") }.getOrDefault(false)) runCatching { Widgets.updateAll(this) }
    }
}
