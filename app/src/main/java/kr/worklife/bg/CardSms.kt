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

    fun ingest(ctx: Context, sender: String, body: String, at: LocalDateTime, source: String): Boolean {
        val repo = Repo.get(ctx)
        val tx = CardParser.parse(sender, body, at, repo.cardKeywords, source) ?: return false
        return repo.addCardTx(tx)
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
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        val ex = sbn.notification?.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        // 출처: 같은 결제가 문자와 알림톡으로 같이 오면 1건으로 합치기 위해 구분
        val source = when {
            pkg.contains("messaging") || pkg.contains("mms") || pkg.contains("sms") -> "sms"
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
            texts += big
        }
        // 보낸 이 이름(예: '비씨카드')이 글자면 앞에 붙여 BC 판별에 쓰기 (RCS 카드형 메시지 알림은 본문에 'BC'가 없을 수 있음)
        val head = if (sender.isEmpty() && title.isNotBlank()) "$title\n" else ""
        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(sbn.postTime), ZoneId.systemDefault())
        var any = false
        for (t in texts.takeLast(5)) {
            // 빠른 거르기: 금액·승인/취소가 없으면 무시 (다른 앱 알림은 저장하지 않음)
            if (!t.contains("원") && !t.contains("USD")) continue
            if (!t.contains("승인") && !t.contains("취소") && !t.contains("결제")) continue
            if (runCatching { CardIngest.ingest(this, sender, head + t, at, source) }.getOrDefault(false)) any = true
        }
        if (any) runCatching { Widgets.updateAll(this) }
    }
}
