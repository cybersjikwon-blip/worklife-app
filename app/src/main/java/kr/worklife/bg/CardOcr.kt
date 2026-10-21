package kr.worklife.bg

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kr.worklife.core.CardImport
import kr.worklife.data.Repo
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 카드 승인 화면 캡처 → 글자인식 → 내역 저장
 * (RCS 카드형 메시지처럼 공유·복사가 안 되는 문자용. 사진은 기기 안에서만 읽고 저장하지 않음)
 */
object CardOcr {
    /** 백그라운드 스레드에서 호출. (찾은 결제 수, 새로 추가된 수) */
    fun importImages(ctx: Context, uris: List<Uri>): Pair<Int, Int> {
        val repo = Repo.get(ctx)
        val rec = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        var found = 0; var added = 0
        try {
            for (u in uris) {
                val text = recognize(ctx, rec, u) ?: continue
                val list = CardImport.parseText(text, LocalDateTime.now(), repo.cardKeywords, "image")
                found += list.size
                added += list.count { repo.addCardTx(it) }
            }
        } finally { runCatching { rec.close() } }
        if (added > 0) runCatching { Widgets.updateAll(ctx) }
        return found to added
    }

    private fun recognize(ctx: Context, rec: com.google.mlkit.vision.text.TextRecognizer, uri: Uri): String? {
        val img = runCatching { InputImage.fromFilePath(ctx, uri) }.getOrNull() ?: return null
        var out: String? = null
        val latch = CountDownLatch(1)
        rec.process(img)
            .addOnSuccessListener { vt -> out = vt.text; latch.countDown() }
            .addOnFailureListener { latch.countDown() }
        latch.await(30, TimeUnit.SECONDS)
        return out
    }
}
