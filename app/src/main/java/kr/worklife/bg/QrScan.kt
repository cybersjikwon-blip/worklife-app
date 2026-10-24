package kr.worklife.bg

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * QR 스캔: 구글 스캐너 화면을 띄움 (앱에 카메라 권한 불필요 → 권한·카메라 오류 없음)
 * 처음 한 번은 구글 Play 서비스가 스캐너를 내려받느라 몇 초 걸릴 수 있음
 */
object QrScan {
    fun scan(ctx: Context, onText: (String) -> Unit, onFail: (String) -> Unit) {
        runCatching {
            val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
            GmsBarcodeScanning.getClient(ctx, opts).startScan()
                .addOnSuccessListener { b -> b.rawValue?.let(onText) ?: onFail("QR을 읽지 못했어요") }
                .addOnCanceledListener { }
                .addOnFailureListener { e -> onFail("스캐너를 열 수 없어요 (${e.message ?: "준비 중"}). 잠시 뒤 다시 시도하거나 번호를 직접 넣어 주세요.") }
        }.onFailure { onFail("스캐너를 열 수 없어요. 번호를 직접 넣어 주세요.") }
    }
}
