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
 * 당첨번호 받기 (동행복권)
 *  ① 개편된 사이트 조회: /lt645/selectPstLt645Info.do?srchLtEpsd=all → 전 회차를 요청 1번에 (가장 빠름)
 *  ② 실패하면 회차별 조회: 같은 주소 srchLtEpsd=회차
 *  ③ 그래도 안 되면 예전 조회 주소(getLottoNumber)
 *  - 받은 회차는 폰에 저장 → 다시 받지 않음, 항상 백그라운드 스레드에서
 */
object LottoNet {
    private const val NEW_URL = "https://www.dhlottery.co.kr/lt645/selectPstLt645Info.do?srchLtEpsd="
    private const val OLD_URL = "https://www.dhlottery.co.kr/common.do?method=getLottoNumber&drwNo="

    /** 마지막 실패 이유 (화면 안내용) */
    @Volatile var lastError: String? = null

    private fun get(u: String, timeout: Int = 8000): String? {
        val c = URL(u).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = timeout; c.readTimeout = timeout * 2; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; SM-S921N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
            c.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01")
            c.setRequestProperty("Referer", "https://www.dhlottery.co.kr/lt645/result")
            c.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            val code = c.responseCode
            if (code != 200) { lastError = "동행복권 응답 $code"; null }
            else c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.also {
                if (it.contains("차단") || it.contains("대기")) lastError = "동행복권 접속이 잠시 막혔어요 (접속 대기/차단). 잠시 뒤 다시 시도해 주세요."
            }
        } catch (e: Exception) { lastError = "인터넷 연결을 확인해 주세요 (${e.javaClass.simpleName})"; null }
        finally { c.disconnect() }
    }

    /** 전 회차 한 번에 */
    fun fetchAll(): List<LottoDraw> {
        repeat(2) { attempt ->
            val list = get(NEW_URL + "all", 10_000)?.let { Lotto.parseNewList(it) }.orEmpty()
            if (list.isNotEmpty()) return list
            if (attempt == 0) Thread.sleep(800)
        }
        return emptyList()
    }

    /** 한 회차 */
    fun fetch(round: Int): LottoDraw? {
        get(NEW_URL + round)?.let { Lotto.parseNewList(it).firstOrNull { d -> d.round == round } }?.let { return it }
        get(OLD_URL + round)?.let { Lotto.parseDrawJson(it) }?.let { return it }
        return null
    }

    /**
     * 최근 want회 중 없는 회차 채우기. progress(받은 수, 받아야 할 수)
     * @return (새로 받은 수, 못 받은 수)
     */
    fun sync(ctx: Context, want: Int = 100, progress: (Int, Int) -> Unit = { _, _ -> }): Pair<Int, Int> {
        lastError = null
        val repo = Repo.get(ctx)
        val latest = Lotto.latestRound()
        val have = repo.drawRounds()
        val need = ((latest - want + 1).coerceAtLeast(1)..latest).filter { it !in have }
        if (need.isEmpty()) return 0 to 0
        // ① 한 번에 전부 (몇 회차가 비었든 요청 1번)
        progress(0, need.size)
        val all = fetchAll()
        if (all.isNotEmpty()) {
            val fresh = all.filter { it.round !in have }
            repo.saveDraws(fresh)
            val got = fresh.count { it.round in need }
            progress(need.size, need.size)
            // 이번 주 추첨 직후엔 아직 목록에 없을 수 있음 → 그 회차만 따로 한 번 더
            val missing = need.filter { r -> all.none { it.round == r } }
            val extra = missing.mapNotNull { fetch(it) }.also { repo.saveDraws(it) }.size
            return (got + extra) to (missing.size - extra)
        }
        // ②③ 회차별로 (4개씩 동시에)
        val done = AtomicInteger(0); val ok = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(4)
        need.sortedDescending().forEach { r ->
            pool.execute {
                fetch(r)?.let { repo.saveDraw(it); ok.incrementAndGet() }
                progress(done.incrementAndGet(), need.size)
            }
        }
        pool.shutdown(); pool.awaitTermination(5, TimeUnit.MINUTES)
        return ok.get() to (need.size - ok.get())
    }
}
