package kr.worklife.bg

import android.app.Notification
import android.content.Context
import android.net.Uri
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kr.worklife.core.CardImport
import kr.worklife.core.CardParser
import kr.worklife.data.Repo
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 카드 승인 알림 → 사용 내역 저장
 *  - 문자 읽기 권한은 쓰지 않음 (갤럭시·Play 프로텍트가 '악성 앱 의심'으로 설치를 막기 때문)
 *  - 대신 ① 알림 접근(문자앱 알림·카카오 알림톡·페이북) ② 문자 공유/붙여넣기 ③ 카드사 이용내역 파일(xlsx/csv/xls)
 *  - 모든 경로가 같은 저장 함수를 거쳐 중복 없이
 */
object CardIngest {
    fun hasNotiAccess(ctx: Context) = runCatching { NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) }.getOrDefault(false)

    fun ingest(ctx: Context, sender: String, body: String, at: LocalDateTime, source: String): Boolean =
        ingestWithResult(ctx, sender, body, at, source) == "추가"

    /** "추가" / "이미 있음" / "카드 승인 아님" */
    fun ingestWithResult(ctx: Context, sender: String, body: String, at: LocalDateTime, source: String): String {
        val repo = Repo.get(ctx)
        val tx = CardParser.parse(sender, body, at, repo.cardKeywords, source) ?: return "카드 승인 아님"
        return if (repo.addCardTx(tx)) "추가" else "이미 있음"
    }

    // ───────── 진단: 알림 연결 상태와 최근 받은 카드 관련 알림 (기기 안에만 보관) ─────────
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("worklife", Context.MODE_PRIVATE)
    fun markConnected(ctx: Context) { prefs(ctx).edit().putLong("notiConnectedAt", System.currentTimeMillis()).apply() }
    fun connectedAt(ctx: Context): Long = prefs(ctx).getLong("notiConnectedAt", 0L)

    fun logNoti(ctx: Context, pkg: String, text: String, result: String) {
        val app = when { pkg.contains("kakao") -> "카톡"; pkg.contains("messag") || pkg.contains("mms") -> "문자"; else -> pkg.substringAfterLast('.') }
        val line = "${java.time.LocalTime.now().withNano(0).toString().take(5)} [$app] $result · " + text.replace("\n", " ").take(60)
        val old = (prefs(ctx).getString("cardNotiLog", "") ?: "").lines().filter { it.isNotBlank() }.takeLast(14)
        prefs(ctx).edit().putString("cardNotiLog", (old + line).joinToString("\n")).apply()
    }
    fun notiLog(ctx: Context): List<String> = (prefs(ctx).getString("cardNotiLog", "") ?: "").lines().filter { it.isNotBlank() }.reversed()

    /** 앱을 열 때: 알림 접근이 켜져 있으면 연결을 다시 요청 (업데이트 후 연결이 끊기는 경우 대비) */
    fun rebind(ctx: Context) {
        if (!hasNotiAccess(ctx)) return
        runCatching { NotificationListenerService.requestRebind(android.content.ComponentName(ctx, CardNotifService::class.java)) }
    }

    /** 공유·붙여넣기 글 → (찾은 결제 수, 새로 추가된 수) */
    fun importText(ctx: Context, text: String): Pair<Int, Int> {
        val repo = Repo.get(ctx)
        val list = CardImport.parseText(text, LocalDateTime.now(), repo.cardKeywords, "share")
        val added = list.count { repo.addCardTx(it) }
        if (added > 0) runCatching { Widgets.updateAll(ctx) }
        return list.size to added
    }

    /** 카드사 이용내역 파일 → (찾은 결제 수, 새로 추가된 수) */
    fun importFile(ctx: Context, uri: Uri): Pair<Int, Int> {
        val repo = Repo.get(ctx)
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return 0 to 0
        val list = CardImport.fromRows(CardImport.rowsFromFile(bytes))
        val added = list.count { repo.addCardTx(it) }
        if (added > 0) runCatching { Widgets.updateAll(ctx) }
        return list.size to added
    }
}

/** 문자앱 알림·카카오 알림톡·페이북/BC 앱 알림 */
class CardNotifService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        CardIngest.markConnected(this)
        // 연결이 끊겨 있던 동안 온 알림도 알림창에 남아 있으면 처리
        runCatching { activeNotifications?.forEach { handle(it, fromBacklog = true) } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) { runCatching { handle(sbn, false) } }

    private fun handle(sbn: StatusBarNotification, fromBacklog: Boolean) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        val ex = sbn.notification?.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        // 출처: 같은 결제가 문자와 알림톡으로 같이 오면 1건으로 합치기 위해 구분
        val source = when {
            pkg.contains("messaging") || pkg.contains("mms") || pkg.contains("sms") || pkg.contains("message") -> "sms"
            pkg.contains("kakao") -> "kakao"
            else -> "noti"
        }
        val sender = title.filter { it.isDigit() }.takeIf { it.length >= 8 && it.length * 2 >= title.length } ?: ""
        // 대화형 알림(문자앱)은 메시지가 여러 개 묶여 올 수 있음 → 하나씩
        val texts = mutableListOf<String>()
        runCatching {
            @Suppress("DEPRECATION")
            ex.getParcelableArray(Notification.EXTRA_MESSAGES)?.forEach { b ->
                (b as? android.os.Bundle)?.getCharSequence("text")?.toString()?.let { texts += it }
            }
        }
        if (texts.isEmpty()) {
            val big = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
            ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.let { lines -> if (lines.isNotEmpty()) lines.forEach { texts += it.toString() } }
            if (texts.isEmpty()) texts += big
        }
        // 보낸 이 이름(예: '비씨카드')을 앞에 붙여 BC 판별에 씀 (카드형(RCS) 메시지 알림은 본문에 'BC'가 없을 수 있음)
        val head = if (title.isNotBlank()) "$title\n" else ""
        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(sbn.postTime), ZoneId.systemDefault())
        var any = false
        for (t in texts.takeLast(6)) {
            val full = head + t
            // 빠른 거르기: 금액이 없으면 무시 (다른 알림은 저장·기록하지 않음)
            if (!Regex("""\d\s*원|USD|달러""").containsMatchIn(full)) continue
            if (!Regex("승인|취소|결제|사용|일시불|개월|카드|BC|비씨", RegexOption.IGNORE_CASE).containsMatchIn(full)) continue
            val r = runCatching { CardIngest.ingestWithResult(this, sender, full, at, source) }.getOrDefault("오류")
            if (!fromBacklog || r == "추가") CardIngest.logNoti(this, pkg, full, r)
            if (r == "추가") any = true
        }
        if (any) runCatching { Widgets.updateAll(this) }
    }
}
