package kr.worklife.bg

import android.content.Context
import kr.worklife.core.Lotto
import kr.worklife.data.Repo
import java.time.LocalDateTime

/**
 * 토요일 21:00 당첨 결과 알림
 *  - 이번 회차 당첨번호를 받아 내 번호(🎫 구매 우선)의 등수를 알림
 *  - 아직 발표 전이면 30분 뒤 다시 (23:30까지)
 */
object LottoResult {
    fun check(ctx: Context) {
        val repo = Repo.get(ctx)
        val round = Lotto.latestRound()
        val mine = repo.myLotto().filter { it.round == round }
        runCatching { LottoNet.sync(ctx, 5) }
        val d = repo.draws().firstOrNull { it.round == round }
        val now = LocalDateTime.now()
        if (d == null) {
            if (now.hour < 23 || (now.hour == 23 && now.minute < 30)) Scheduler.scheduleLotto(ctx, now.plusMinutes(30))
            else Scheduler.scheduleLotto(ctx)
            return
        }
        Scheduler.scheduleLotto(ctx) // 다음 주
        if (mine.isEmpty()) return
        val bought = mine.filter { it.src != "생성" }
        val target = bought.ifEmpty { mine }
        val ranks = target.map { Lotto.rank(it.nums, d) }
        val best = ranks.filter { it > 0 }.minOrNull()
        Notif.init(ctx)
        val nums = d.nums.joinToString(" ") + " + " + d.bonus
        val who = if (bought.isEmpty()) "저장한 번호" else "구매한 번호"
        if (best != null) Notif.post(ctx, 50, "🎉 제${round}회 ${best}등 당첨!",
            "$who ${target.size}게임 중 " + ranks.filter { it > 0 }.groupBy { it }.toSortedMap().entries.joinToString(", ") { "${it.key}등 ${it.value.size}개" } + " · 당첨번호 $nums")
        else Notif.post(ctx, 50, "제${round}회 결과: 이번 주는 아쉽게 낙첨", "$who ${target.size}게임 · 당첨번호 $nums")
    }
}
