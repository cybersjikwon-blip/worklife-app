package kr.worklife.bg

import android.content.Context
import kr.worklife.core.Lotto
import kr.worklife.core.LottoDraw
import kr.worklife.data.Repo
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 당첨번호 받기 (동행복권 공개 조회)
 *  - 한 번 받은 회차는 폰에 저장 → 다시 받지 않음 (렉 없음)
 *  - 4개씩 동시에 받고, 실패한 회차는 주소를 바꿔 2번 더 시도
 *  - 화면을 막지 않도록 항상 백그라운드 스레드에서 호출
 */
object LottoNet {
    private val URLS = listOf(
        "https://www.dhlottery.co.kr/common.do?method=getLottoNumber&drwNo=",
        "https://dhlottery.co.kr/common.do?method=getLottoNumber&drwNo=",
        "http://www.dhlottery.co.kr/common.do?method=getLottoNumber&drwNo=")

    fun fetch(round: Int): LottoDraw? {
        for (attempt in 0 until 3) {
            val d = runCatching { get(URLS[attempt % URLS.size] + round)?.let { Lotto.parseDrawJson(it) } }.getOrNull()
            if (d != null) return d
            if (attempt < 2) Thread.sleep(300L * (attempt + 1))
        }
        return null
    }

    private fun get(u: String): String? {
        val c = URL(u).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 6000; c.readTimeout = 6000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
            c.setRequestProperty("Accept", "application/json,text/plain,*/*")
            if (c.responseCode != 200) null else c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
    }

    /**
     * 최근 want회 중 없는 회차만 받기. progress(받은 수, 받아야 할 수).
     * @return (새로 받은 수, 실패 수) — 이번 주 추첨 직후엔 아직 발표 전이라 최신 회차가 실패할 수 있음(정상)
     */
    fun sync(ctx: Context, want: Int = 100, progress: (Int, Int) -> Unit = { _, _ -> }): Pair<Int, Int> {
        val repo = Repo.get(ctx)
        val latest = Lotto.latestRound()
        val have = repo.drawRounds()
        val need = ((latest - want + 1).coerceAtLeast(1)..latest).filter { it !in have }.sortedDescending()
        if (need.isEmpty()) return 0 to 0
        val done = AtomicInteger(0); val ok = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(4)
        need.forEach { r ->
            pool.execute {
                fetch(r)?.let { repo.saveDraw(it); ok.incrementAndGet() }
                progress(done.incrementAndGet(), need.size)
            }
        }
        pool.shutdown(); pool.awaitTermination(5, TimeUnit.MINUTES)
        return ok.get() to (need.size - ok.get())
    }
}
