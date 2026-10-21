package kr.worklife.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * 카드 사용액 합산 (BC카드 승인 문자 / 카카오 알림톡·페이북 알림)
 *  - 문자 형식이 카드·은행마다 달라서 '승인/취소 + 금액 + 카드 표시'를 각각 찾아 조합
 *  - 취소는 음수로 저장 → 합계에서 자동 차감
 *  - 같은 결제가 문자와 알림으로 두 번 와도 1건으로 (중복 판정은 Repo에서)
 */
data class CardTx(
    val id: Long = 0,
    val ts: LocalDateTime,
    val amount: Long,              // 원. 취소는 음수
    val merchant: String = "",
    val card: String = "",          // 카드 끝자리 (예: 1234, 1*2*)
    val sender: String = "",        // 문자 보낸 번호 (가족 폰에서 전달된 문자면 그 번호)
    val name: String = "",          // 문자 속 이름 (홍*동)
    val installment: Int = 0,       // 0 = 일시불
    val foreign: String = "",       // 해외 결제 원래 금액 (예: USD 12.50) — 원화 미확정
    val isBc: Boolean = false,
    val source: String = "sms",     // sms / noti / manual
    val raw: String = "",
    val excluded: Boolean = false,
    val person: Int = -1,           // 직접 지정한 사람 (-1 = 자동 매칭)
) {
    val isCancel get() = amount < 0
}

/** 합산할 사람 (최대 5명). 카드 끝자리·전화번호·이름 중 하나라도 맞으면 그 사람 */
data class CardPerson(val name: String, val phone: String = "", val cards: List<String> = emptyList(), val nameHint: String = "") {
    companion object { const val MAX = 5 }
}

/** 집계 기간 설정 */
data class CardPeriod(
    val mode: String = "month",      // month(이번 달) / last(지난 달) / cycle(카드 이용기간) / custom(직접)
    val cycleStartDay: Int = 1,       // 카드 이용기간 시작일 (예: 매월 1일, 14일…)
    val customStart: LocalDate? = null,
    val customEnd: LocalDate? = null, // null = 오늘까지
)

fun cardRange(p: CardPeriod, today: LocalDate): Pair<LocalDate, LocalDate> = when (p.mode) {
    "last" -> YearMonth.from(today).minusMonths(1).let { it.atDay(1) to it.atEndOfMonth() }
    "cycle" -> {
        val sd = p.cycleStartDay.coerceIn(1, 28)
        val start = if (today.dayOfMonth >= sd) today.withDayOfMonth(sd) else today.minusMonths(1).withDayOfMonth(sd)
        start to start.plusMonths(1).minusDays(1)
    }
    "custom" -> {
        val s = p.customStart ?: today.withDayOfMonth(1)
        val e = p.customEnd ?: today
        if (e.isBefore(s)) e to s else s to e
    }
    else -> today.withDayOfMonth(1) to YearMonth.from(today).atEndOfMonth()
}

fun cardRangeLabel(p: CardPeriod, today: LocalDate): String {
    val (s, e) = cardRange(p, today)
    val name = when (p.mode) { "last" -> "지난 달"; "cycle" -> "카드 이용기간"; "custom" -> "직접 지정"; else -> "이번 달" }
    return "$name ${s.monthValue}/${s.dayOfMonth} ~ ${e.monthValue}/${e.dayOfMonth}" + (if (p.mode == "custom" && p.customEnd == null) " (오늘까지)" else "")
}

object CardParser {
    val DEFAULT_KEYWORDS = listOf("BC", "비씨", "페이북", "바로카드")

    private val amountRx = Regex("""(?<![\d.])(\d{1,3}(?:,\d{3})+|\d{1,9})\s*원""")
    private val foreignRx = Regex("""(USD|JPY|EUR|CNY|GBP|\$|달러|엔|유로)\s*([\d,]+(?:\.\d+)?)|([\d,]+(?:\.\d+)?)\s*(USD|JPY|EUR|CNY|GBP|달러|엔|유로)""")
    private val cardRx = Regex("""[(\[]\s*(\d[\d*]{2,3})\s*[)\]]|(\d{4})\s*\*|\*\s*(\d{4})|카드\s*(\d{4})|끝\s*(\d{4})""")
    private val dtRx = Regex("""(\d{1,2})[/.-](\d{1,2})\s*(\d{1,2}):(\d{2})""")
    private val nameRx = Regex("""([가-힣][*＊][가-힣]{1,2})|([가-힣]{2,4})\s*님""")
    private val instRx = Regex("""(\d{1,2})\s*개월""")
    private val skipLine = Regex("""누적|잔여|잔액|한도|승인|취소|Web발신|국외발신|^\s*\[|원\s*$|^\s*\d{1,2}[/.-]\d{1,2}|일시불|개월|님$|카드\s*\(|^\s*[(\[]\d""")
    private val rejectRx = Regex("""거절|승인\s*불가|한도\s*초과|잔액\s*부족|비밀번호|오류|미승인|결제\s*예정|청구|명세서|안내드립니다|출금|입금|이체""")
    private val approveRx = Regex("""승인|결제|사용|일시불|개월""")

    /** 카드 승인/취소 메시지면 CardTx, 아니면 null */
    fun parse(sender: String, body: String, received: LocalDateTime, keywords: List<String> = DEFAULT_KEYWORDS, source: String = "sms"): CardTx? {
        val text = body.replace('\r', '\n').trim()
        if (text.isEmpty()) return null
        val cancel = text.contains("취소")
        if (!cancel && rejectRx.containsMatchIn(text)) return null
        if (!cancel && !approveRx.containsMatchIn(text)) return null
        // 카드 문자인지: 카드 표시나 카드사 이름이 있어야 함 (은행 입출금 문자 제외)
        val cardM = cardRx.find(text)
        val card = cardM?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() } ?: ""
        val isBc = keywords.any { k -> k.isNotBlank() && text.contains(k, ignoreCase = true) }
        if (card.isEmpty() && !text.contains("카드") && !isBc) return null

        // 금액: '누적/잔여/한도' 바로 뒤 금액은 제외하고 첫 번째
        var amount: Long? = null
        for (m in amountRx.findAll(text)) {
            val before = text.substring(maxOf(0, m.range.first - 6), m.range.first)
            if (Regex("누적|잔여|잔액|한도|포인트|적립").containsMatchIn(before)) continue
            amount = m.groupValues[1].replace(",", "").toLongOrNull(); break
        }
        var foreign = ""
        if (amount == null) {
            val f = foreignRx.find(text) ?: return null
            foreign = f.value.trim()
            amount = 0
        }
        if (amount == null || amount < 0) return null

        // 시간: 문자 속 '10/21 14:23' 우선, 없으면 받은 시각. 연도는 받은 날 기준(1월에 받은 12월 문자 → 작년)
        val ts = dtRx.find(text)?.let { m ->
            val (mo, d, h, mi) = m.destructured
            runCatching {
                var y = received.year
                if (mo.toInt() > received.monthValue + 1) y -= 1
                LocalDateTime.of(y, mo.toInt(), d.toInt(), h.toInt(), mi.toInt())
            }.getOrNull()
        } ?: received.withSecond(0).withNano(0)

        val nm = nameRx.find(text)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } ?: ""
        val inst = instRx.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return CardTx(ts = ts, amount = if (cancel) -amount else amount, merchant = merchantOf(text), card = card,
            sender = normPhone(sender), name = nm, installment = inst, foreign = foreign, isBc = isBc, source = source, raw = text.take(300))
    }

    /** 가맹점: 금액·시간·카드 표시가 아닌 줄 중 마지막 (한 줄 문자면 시간 뒤 글자) */
    fun merchantOf(text: String): String {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size >= 3) {
            val cand = lines.drop(1).filter { !skipLine.containsMatchIn(it) && !amountRx.containsMatchIn(it) }
            cand.lastOrNull()?.let { return it.take(30) }
        }
        val m = dtRx.find(text) ?: return ""
        return text.substring(m.range.last + 1).split(Regex("누적|잔여|\n")).first()
            .replace(Regex("""\d{1,3}(?:,\d{3})+\s*원|\d+\s*원|일시불|\d+\s*개월|승인|취소"""), " ").trim().take(30)
    }

    fun normPhone(s: String) = s.filter { it.isDigit() }.let { if (it.startsWith("82") && it.length > 10) "0" + it.drop(2) else it }
}

/** 사람 매칭: 직접 지정 > 카드 끝자리 > 보낸 번호 > 이름. 없으면 -1 (미지정) */
fun matchPerson(tx: CardTx, people: List<CardPerson>): Int {
    if (tx.person in people.indices) return tx.person
    if (tx.card.isNotEmpty()) people.indexOfFirst { p -> p.cards.any { cardEq(it, tx.card) } }.let { if (it >= 0) return it }
    if (tx.sender.length >= 9) people.indexOfFirst { CardParser.normPhone(it.phone).let { ph -> ph.isNotEmpty() && ph == tx.sender } }.let { if (it >= 0) return it }
    if (tx.name.isNotEmpty()) people.indexOfFirst { it.nameHint.isNotBlank() && nameEq(it.nameHint, tx.name) }.let { if (it >= 0) return it }
    return -1
}

/** 끝자리 비교 ('1*2*' 같은 가림 표시 허용) */
fun cardEq(a: String, b: String): Boolean {
    val x = a.trim(); val y = b.trim()
    if (x.isEmpty() || y.isEmpty() || x.length != y.length) return x == y
    return x.indices.all { x[it] == y[it] || x[it] == '*' || y[it] == '*' }
}

fun nameEq(a: String, b: String): Boolean {
    val x = a.trim(); val y = b.trim()
    if (x == y) return true
    if (x.length != y.length) return false
    return x.indices.all { x[it] == y[it] || x[it] == '*' || y[it] == '*' || x[it] == '＊' || y[it] == '＊' }
}

/** 합산에 넣을지: 제외 안 했고, (사람이 정해졌거나 BC 문자) */
fun cardIncluded(tx: CardTx, people: List<CardPerson>, bcOnly: Boolean): Boolean =
    !tx.excluded && (!bcOnly || tx.isBc || matchPerson(tx, people) >= 0 || tx.source == "manual")

data class CardSummary(val total: Long, val count: Int, val cancelTotal: Long, val perPerson: List<Pair<Long, Int>>,
                       val unassigned: Pair<Long, Int>, val foreignPending: Int, val otherCards: Map<String, Long>)

fun summarizeCards(txs: List<CardTx>, people: List<CardPerson>, from: LocalDate, to: LocalDate, bcOnly: Boolean): CardSummary {
    val inRange = txs.filter { !it.ts.toLocalDate().isBefore(from) && !it.ts.toLocalDate().isAfter(to) }
    val inc = inRange.filter { cardIncluded(it, people, bcOnly) }
    val per = people.indices.map { i -> inc.filter { matchPerson(it, people) == i }.let { l -> l.sumOf { it.amount } to l.count { !it.isCancel } } }
    val un = inc.filter { matchPerson(it, people) < 0 }.let { l -> l.sumOf { it.amount } to l.count { !it.isCancel } }
    val others = inRange.filter { !it.excluded && !cardIncluded(it, people, bcOnly) }
        .groupBy { it.card.ifEmpty { "끝자리 없음" } }.mapValues { (_, l) -> l.sumOf { it.amount } }
    return CardSummary(inc.sumOf { it.amount }, inc.count { !it.isCancel }, inc.filter { it.isCancel }.sumOf { it.amount },
        per, un, inc.count { it.foreign.isNotEmpty() && it.amount == 0L }, others)
}

/** 같은 결제인지 (문자 + 알림톡 중복): 같은 카드·금액·부호, 3분 이내, 출처가 다르거나 원문이 같음 */
fun sameCardTx(a: CardTx, b: CardTx): Boolean =
    a.amount == b.amount && cardEq(a.card, b.card) && kotlin.math.abs(ChronoUnit.MINUTES.between(a.ts, b.ts)) <= 3 &&
        (a.source != b.source || a.raw == b.raw)
