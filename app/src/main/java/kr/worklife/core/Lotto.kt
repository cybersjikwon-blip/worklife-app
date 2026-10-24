package kr.worklife.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/** 로또 6/45 — 번호 생성·당첨 확인·QR 해석·통계 (화면과 분리해서 테스트 가능) */
data class LottoDraw(val round: Int, val date: LocalDate, val nums: List<Int>, val bonus: Int, val firstPrize: Long = 0, val firstWinners: Int = 0)

data class LottoOptions(
    val fixed: Set<Int> = emptySet(),     // 꼭 넣을 번호 (최대 5개)
    val exclude: Set<Int> = emptySet(),   // 뺄 번호
    val balanceOddEven: Boolean = false,  // 홀짝 2:4 ~ 4:2
    val sumRange: Boolean = false,        // 합계 100~175 (역대 당첨의 약 70%)
    val maxConsecutive: Int = 0,          // 연속번호 최대 개수 (0 = 제한 없음, 2 = 2연속까지만)
    val avoidLastDraw: Boolean = false,   // 지난 회차 당첨번호 빼기
)

object Lotto {
    val FIRST_DRAW: LocalDate = LocalDate.of(2002, 12, 7)
    private val DRAW_TIME: LocalTime = LocalTime.of(20, 45)

    /** 그 시각 기준 '이미 추첨이 끝난' 최신 회차 */
    fun latestRound(now: LocalDateTime = LocalDateTime.now()): Int {
        var sat = now.toLocalDate()
        while (sat.dayOfWeek != DayOfWeek.SATURDAY) sat = sat.minusDays(1)
        if (now.toLocalDate() == sat && now.toLocalTime().isBefore(DRAW_TIME)) sat = sat.minusWeeks(1)
        return (ChronoUnit.WEEKS.between(FIRST_DRAW, sat) + 1).toInt()
    }

    /** 이번에 살 회차 (다음 추첨) */
    fun nextRound(now: LocalDateTime = LocalDateTime.now()) = latestRound(now) + 1
    fun dateOf(round: Int): LocalDate = FIRST_DRAW.plusWeeks((round - 1).toLong())

    /** 조건에 맞는 한 게임. 조건이 너무 빡빡하면 조건을 하나씩 풀어서라도 반드시 결과를 냄 */
    fun generate(o: LottoOptions, last: LottoDraw? = null, rnd: Random = Random.Default): List<Int> {
        val fixed = o.fixed.filter { it in 1..45 }.take(5).toSet()
        val banned = (o.exclude + (if (o.avoidLastDraw && last != null) last.nums else emptyList())) - fixed
        val pool = (1..45).filter { it !in banned && it !in fixed }
        if (pool.size + fixed.size < 6) return ((1..45).filter { it !in fixed }.shuffled(rnd).take(6 - fixed.size) + fixed).sorted()
        repeat(3000) {
            val pick = (pool.shuffled(rnd).take(6 - fixed.size) + fixed).sorted()
            if (ok(pick, o)) return pick
        }
        repeat(500) { val pick = (pool.shuffled(rnd).take(6 - fixed.size) + fixed).sorted(); if (ok(pick, o.copy(sumRange = false))) return pick }
        return (pool.shuffled(rnd).take(6 - fixed.size) + fixed).sorted()
    }

    fun ok(p: List<Int>, o: LottoOptions): Boolean {
        if (o.balanceOddEven) { val odd = p.count { it % 2 == 1 }; if (odd < 2 || odd > 4) return false }
        if (o.sumRange) { val s = p.sum(); if (s < 100 || s > 175) return false }
        if (o.maxConsecutive > 0 && longestRun(p) > o.maxConsecutive) return false
        return true
    }

    fun longestRun(p: List<Int>): Int {
        var best = 1; var cur = 1
        for (i in 1 until p.size) { if (p[i] == p[i - 1] + 1) { cur++; best = maxOf(best, cur) } else cur = 1 }
        return best
    }

    /** 등수: 1~5, 0 = 낙첨 */
    fun rank(game: List<Int>, d: LottoDraw): Int {
        val m = game.count { it in d.nums }
        return when {
            m == 6 -> 1
            m == 5 && d.bonus in game -> 2
            m == 5 -> 3
            m == 4 -> 4
            m == 3 -> 5
            else -> 0
        }
    }

    fun rankLabel(r: Int) = if (r == 0) "낙첨" else "${r}등"

    /** 공 색 (동행복권 기준): 1~10 노랑, 11~20 파랑, 21~30 빨강, 31~40 회색, 41~45 초록 */
    fun ballColor(n: Int): Long = when (n) {
        in 1..10 -> 0xFFFBC400; in 11..20 -> 0xFF69C8F2; in 21..30 -> 0xFFFF7272; in 31..40 -> 0xFFAAAAAA; else -> 0xFFB0D840
    }

    /**
     * 로또 용지 QR: "http://m.dhlottery.co.kr/?v=1190q031117263845q0102...m..."
     * v= 뒤 4자리 회차, 이후 [q/m/n/s/...] 구분자 + 2자리×6 게임 반복 (뒤에 붙는 일련번호는 무시)
     */
    fun parseQr(text: String): Pair<Int, List<List<Int>>>? {
        val v = Regex("""[?&]v=(\d{3,4})((?:[a-z]\d{12})+)""").find(text) ?: return null
        val round = v.groupValues[1].toInt()
        val games = Regex("""[a-z](\d{12})""").findAll(v.groupValues[2]).mapNotNull { g ->
            val n = g.groupValues[1].chunked(2).map { it.toInt() }
            if (n.all { it in 1..45 } && n.toSet().size == 6) n.sorted() else null
        }.toList()
        return if (games.isEmpty()) null else round to games
    }

    /** 동행복권 회차 조회 응답(JSON) 해석. 실패면 null */
    fun parseDrawJson(js: String): LottoDraw? = runCatching {
        if (!js.contains("\"returnValue\":\"success\"")) return null
        fun num(k: String) = Regex("\"$k\"\\s*:\\s*\"?(\\d+)").find(js)!!.groupValues[1]
        val nums = (1..6).map { num("drwtNo$it").toInt() }.sorted()
        LottoDraw(num("drwNo").toInt(), LocalDate.parse(Regex("\"drwNoDate\"\\s*:\\s*\"([\\d-]+)\"").find(js)!!.groupValues[1]),
            nums, num("bnusNo").toInt(), runCatching { num("firstWinamnt").toLong() }.getOrDefault(0), runCatching { num("firstPrzwnerCo").toInt() }.getOrDefault(0))
    }.getOrNull()

    /**
     * 2025 개편 사이트 응답: /lt645/selectPstLt645Info.do?srchLtEpsd=all (또는 회차 번호)
     * {"data":{"list":[{"ltEpsd":1190,"ltRflYmd":"20250927","tm1WnNo":3,…,"tm6WnNo":45,"bnsWnNo":9,"rnk1WnAmt":…,"rnk1WnNope":…},…]}}
     * 필드가 숫자든 문자열이든, 순서가 어떻든 읽음
     */
    fun parseNewList(js: String): List<LottoDraw> =
        Regex("""\{[^{}]*"ltEpsd"[^{}]*\}""").findAll(js).mapNotNull { m ->
            val o = m.value
            fun f(k: String) = Regex("\"$k\"\\s*:\\s*\"?(\\d+)").find(o)?.groupValues?.get(1)
            runCatching {
                val nums = (1..6).map { f("tm${it}WnNo")!!.toInt() }
                val ymd = f("ltRflYmd")!!
                if (nums.any { it !in 1..45 } || nums.toSet().size != 6) null
                else LottoDraw(f("ltEpsd")!!.toInt(), LocalDate.of(ymd.take(4).toInt(), ymd.substring(4, 6).toInt(), ymd.substring(6, 8).toInt()),
                    nums.sorted(), f("bnsWnNo")!!.toInt(), f("rnk1WnAmt")?.toLongOrNull() ?: 0,
                    (f("rnk1WnNope") ?: f("rnk1WnCnt") ?: f("rnk1WinnerCnt"))?.toIntOrNull() ?: 0)
            }.getOrNull()
        }.toList()

    data class Stats(val count: Int, val freq: IntArray, val lastSeen: IntArray, val hot: List<Int>, val cold: List<Int>, val longGap: List<Int>,
                     val oddAvg: Double, val sumAvg: Double)

    /** 최근 n회 통계: 번호별 출현 수, 마지막 출현 회차, 많이/적게 나온 번호, 오래 안 나온 번호 */
    fun stats(draws: List<LottoDraw>, lastN: Int = 100): Stats {
        val ds = draws.sortedByDescending { it.round }.take(lastN)
        val freq = IntArray(46); val last = IntArray(46)
        for (d in ds) for (n in d.nums) { freq[n]++; if (last[n] == 0) last[n] = d.round }
        val latest = ds.firstOrNull()?.round ?: 0
        val nums = (1..45).toList()
        return Stats(ds.size, freq, last,
            nums.sortedWith(compareByDescending<Int> { freq[it] }.thenBy { it }).take(6),
            nums.sortedWith(compareBy<Int> { freq[it] }.thenBy { it }).take(6),
            nums.sortedByDescending { if (last[it] == 0) Int.MAX_VALUE else latest - last[it] }.take(6),
            if (ds.isEmpty()) 0.0 else ds.map { d -> d.nums.count { it % 2 == 1 } }.average(),
            if (ds.isEmpty()) 0.0 else ds.map { it.nums.sum() }.average())
    }
}
