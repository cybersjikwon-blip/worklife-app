package kr.worklife.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 여행 기록 — 다니면서(현장에서) 빠르게 남기고, 나중에 정리·다시 가기
 *  Trip(여행) ─ Spot(먹거리/볼거리/숙소/들른 곳) ─ Expense(경비) ─ TrackPoint(자동 경로)
 */
data class Trip(
    val id: Long = 0, val title: String, val start: LocalDate, val end: LocalDate? = null,
    val companions: String = "", val rating: Double = 0.0, val fav: Boolean = false,
    val tags: List<String> = emptyList(), val memo: String = "", val active: Boolean = false, val cover: String = "",
) {
    val lastDay get() = end ?: start
    val nights get() = ChronoUnit.DAYS.between(start, lastDay).toInt().coerceAtLeast(0)
    fun days(today: LocalDate = LocalDate.now()): List<LocalDate> {
        val e = end ?: (if (active) maxOf(today, start) else start)
        return generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(e) }.take(60).toList()
    }
    fun contains(d: LocalDate, today: LocalDate = LocalDate.now()) = !d.isBefore(start) && !d.isAfter(end ?: (if (active) maxOf(today, start) else start))
    val periodText get() = if (end == null || end == start) "${start.year}.${start.monthValue}.${start.dayOfMonth}" + (if (active) " ~ 여행 중" else "")
        else "${start.year}.${start.monthValue}.${start.dayOfMonth} ~ ${end.monthValue}.${end.dayOfMonth} (${nights}박 ${nights + 1}일)"
}

object SpotKind {
    const val FOOD = "food"; const val SIGHT = "sight"; const val STAY = "stay"; const val PLACE = "place"
    val all = listOf(FOOD, SIGHT, STAY, PLACE)
    fun label(k: String) = when (k) { FOOD -> "먹거리"; SIGHT -> "볼거리"; STAY -> "숙소"; else -> "들른 곳" }
    fun icon(k: String) = when (k) { FOOD -> "🍽"; SIGHT -> "👀"; STAY -> "🛏"; else -> "📍" }
}

data class Spot(
    val id: Long = 0, val tripId: Long, val kind: String = SpotKind.PLACE, val name: String = "",
    val ts: LocalDateTime, val lat: Double = 0.0, val lon: Double = 0.0,
    val rating: Double = 0.0, val price: Long = 0, val menu: String = "", val memo: String = "",
    val again: Boolean = false, val photos: List<String> = emptyList(),
    // 숙소 전용
    val stayType: String = "", val nights: Int = 0, val booking: String = "", val revisit: Boolean = false,
    // 이동 (이 장소까지 어떻게 왔는지)
    val transport: String = "",
) {
    val hasLocation get() = lat != 0.0 || lon != 0.0
    val title get() = name.ifBlank { "${SpotKind.label(kind)} ${ts.toLocalTime().hhmm()}" }
}

object ExpenseCat {
    val all = listOf("숙박", "식비", "교통", "입장료", "쇼핑", "기타")
    fun icon(c: String) = when (c) { "숙박" -> "🛏"; "식비" -> "🍽"; "교통" -> "🚗"; "입장료" -> "🎟"; "쇼핑" -> "🛍"; else -> "💸" }
    /** 가맹점 이름으로 항목 추측 (카드 결제 불러올 때) */
    fun guess(merchant: String): String {
        val m = merchant.lowercase()
        return when {
            listOf("호텔", "모텔", "펜션", "리조트", "게스트", "민박", "캠핑", "글램핑", "콘도", "숙박", "야놀자", "여기어때", "에어비앤비", "airbnb", "hotel").any { it in m } -> "숙박"
            listOf("주유", "오일", "에너지", "휴게소", "톨", "도로공사", "하이패스", "코레일", "ktx", "srt", "택시", "카카오t", "고속", "버스", "항공", "air", "렌터카", "렌트", "주차", "여객", "선박", "페리").any { it in m } -> "교통"
            listOf("입장", "매표", "관광", "박물관", "미술관", "테마파크", "랜드", "아쿠아", "케이블카", "체험", "공원", "티켓").any { it in m } -> "입장료"
            listOf("식당", "음식", "횟집", "고기", "갈비", "국밥", "치킨", "피자", "카페", "커피", "스타벅스", "베이커리", "빵", "분식", "김밥", "면", "회", "식육", "푸드", "편의점", "gs25", "cu", "세븐일레븐", "이마트24").any { it in m } -> "식비"
            listOf("마트", "쇼핑", "아울렛", "백화점", "기념품", "다이소", "올리브영").any { it in m } -> "쇼핑"
            else -> "기타"
        }
    }
}

data class Expense(val id: Long = 0, val tripId: Long, val ts: LocalDateTime, val category: String = "기타",
                   val amount: Long, val memo: String = "", val cardTxId: Long = -1, val method: String = "현금")

data class TrackPoint(val tripId: Long, val ts: LocalDateTime, val lat: Double, val lon: Double)

object Travel {
    /** 메모·제목에서 #태그 뽑기 (#바다 #캠핑) */
    fun tagsOf(text: String): List<String> = Regex("""#([0-9A-Za-z가-힣_]{1,20})""").findAll(text).map { it.groupValues[1] }.distinct().toList()

    fun parseTags(input: String): List<String> =
        input.split(Regex("[,\\s#]+")).map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(20)

    data class ExpenseSummary(val total: Long, val byCat: Map<String, Long>, val manual: Long, val card: Long, val count: Int)

    /** 경비 합계: 직접 입력 + 불러온 카드 결제 (같은 결제가 두 번 들어가지 않게 cardTxId로 구분) */
    fun summarize(ex: List<Expense>): ExpenseSummary {
        val uniq = ex.distinctBy { if (it.cardTxId > 0) "c${it.cardTxId}" else "m${it.id}-${it.ts}-${it.amount}" }
        return ExpenseSummary(uniq.sumOf { it.amount }, ExpenseCat.all.associateWith { c -> uniq.filter { it.category == c }.sumOf { it.amount } }.filterValues { it != 0L },
            uniq.filter { it.cardTxId <= 0 }.sumOf { it.amount }, uniq.filter { it.cardTxId > 0 }.sumOf { it.amount }, uniq.size)
    }

    /** 여행 기간 카드 결제 중 아직 경비로 안 불러온 것 */
    fun cardCandidates(trip: Trip, txs: List<CardTx>, ex: List<Expense>, today: LocalDate = LocalDate.now()): List<CardTx> {
        val linked = ex.filter { it.cardTxId > 0 }.map { it.cardTxId }.toSet()
        return txs.filter { trip.contains(it.ts.toLocalDate(), today) && it.id !in linked && !it.excluded && it.amount != 0L }.sortedBy { it.ts }
    }

    fun fromCard(trip: Trip, t: CardTx) = Expense(tripId = trip.id, ts = t.ts, category = ExpenseCat.guess(t.merchant), amount = t.amount,
        memo = listOf(t.merchant, t.memo).filter { it.isNotBlank() }.joinToString(" · "), cardTxId = t.id, method = "카드")

    /** 일정표 한 줄 */
    data class Entry(val ts: LocalDateTime, val spot: Spot? = null, val expense: Expense? = null)

    data class Day(val date: LocalDate, val entries: List<Entry>, val km: Double, val spend: Long)

    /** 날짜별 일정표: 장소·경비를 시간순으로, 그날 이동거리(자동 경로)·지출 */
    fun timeline(trip: Trip, spots: List<Spot>, ex: List<Expense>, track: List<TrackPoint>, today: LocalDate = LocalDate.now()): List<Day> {
        val days = (trip.days(today) + spots.map { it.ts.toLocalDate() } + ex.map { it.ts.toLocalDate() }).distinct().sorted()
        return days.map { d ->
            val e = spots.filter { it.ts.toLocalDate() == d }.map { Entry(it.ts, spot = it) } +
                ex.filter { it.ts.toLocalDate() == d && it.cardTxId <= 0 && spots.none { s -> s.price > 0 && s.price == it.amount && s.ts == it.ts } }.map { Entry(it.ts, expense = it) }
            Day(d, e.sortedBy { it.ts }, routeKm(track.filter { it.ts.toLocalDate() == d }), ex.filter { it.ts.toLocalDate() == d }.sumOf { it.amount })
        }
    }

    /** 자동 경로 거리(km): 너무 가까운 점(50m 미만)은 GPS 흔들림으로 보고 무시 */
    fun routeKm(pts: List<TrackPoint>): Double {
        var km = 0.0
        var last: TrackPoint? = null
        for (p in pts.sortedBy { it.ts }) {
            val l = last
            if (l == null) { last = p; continue }
            val d = haversineM(l.lat, l.lon, p.lat, p.lon)
            if (d >= 50) { km += d / 1000; last = p }
        }
        return km
    }

    /** 자동 경로 점을 남길지: 마지막 점에서 100m 이상 움직였거나 2시간 지났으면 */
    fun shouldTrack(last: TrackPoint?, lat: Double, lon: Double, now: LocalDateTime): Boolean =
        last == null || haversineM(last.lat, last.lon, lat, lon) >= 100 || ChronoUnit.MINUTES.between(last.ts, now) >= 120

    /** '그 코스 그대로' 길찾기: 구글 지도 경로 주소 (키 불필요, 경유지 최대 8곳) */
    fun courseUrl(spots: List<Spot>): String? {
        val pts = spots.filter { it.hasLocation }.sortedBy { it.ts }
        if (pts.isEmpty()) return null
        fun ll(s: Spot) = "${"%.6f".format(s.lat)},${"%.6f".format(s.lon)}"
        if (pts.size == 1) return "https://www.google.com/maps/dir/?api=1&destination=${ll(pts[0])}"
        val way = pts.drop(1).dropLast(1).let { if (it.size > 8) it.chunked((it.size + 7) / 8).map { c -> c.first() } else it }
        return "https://www.google.com/maps/dir/?api=1&origin=${ll(pts.first())}&destination=${ll(pts.last())}" +
            (if (way.isNotEmpty()) "&waypoints=" + way.joinToString("%7C") { ll(it) } else "") + "&travelmode=driving"
    }

    /** 사진 묶어서 장소 만들기: 200m·1시간 안에 찍힌 사진끼리 한 장소 */
    data class PhotoInfo(val path: String, val ts: LocalDateTime?, val lat: Double, val lon: Double)

    fun groupPhotos(ps: List<PhotoInfo>): List<List<PhotoInfo>> {
        val withLoc = ps.filter { it.lat != 0.0 || it.lon != 0.0 }.sortedBy { it.ts ?: LocalDateTime.MIN }
        val groups = mutableListOf<MutableList<PhotoInfo>>()
        for (p in withLoc) {
            val g = groups.lastOrNull()
            val h = g?.last()
            if (g != null && h != null && haversineM(h.lat, h.lon, p.lat, p.lon) <= 200 &&
                (p.ts == null || h.ts == null || kotlin.math.abs(ChronoUnit.MINUTES.between(h.ts, p.ts)) <= 60)) g += p
            else groups += mutableListOf(p)
        }
        return groups
    }

    /** 검색: 제목·메모·동행·장소 이름·메뉴·태그 / 태그 / ♥ / 별점 */
    fun search(trips: List<Trip>, spotsByTrip: Map<Long, List<Spot>>, q: String, tag: String? = null, favOnly: Boolean = false, minRating: Double = 0.0): List<Trip> {
        val qq = q.trim().lowercase()
        return trips.filter { t ->
            (tag == null || tag in t.tags) && (!favOnly || t.fav || spotsByTrip[t.id].orEmpty().any { it.again }) &&
                (minRating <= 0 || t.rating >= minRating) &&
                (qq.isEmpty() || listOf(t.title, t.memo, t.companions, t.tags.joinToString(" ")).any { it.lowercase().contains(qq) } ||
                    spotsByTrip[t.id].orEmpty().any { s -> listOf(s.name, s.menu, s.memo, s.booking).any { it.lowercase().contains(qq) } })
        }
    }

    /** '1년 전 오늘': 오늘과 같은 날짜(월·일)에 여행 중이었던 지난 여행 → (여행, 몇 년 전) */
    fun onThisDay(trips: List<Trip>, today: LocalDate): List<Pair<Trip, Int>> = trips.mapNotNull { t ->
        if (t.active) return@mapNotNull null
        val hit = t.days(today).firstOrNull { it.monthValue == today.monthValue && it.dayOfMonth == today.dayOfMonth && it.year < today.year }
        hit?.let { t to (today.year - it.year) }
    }

    /** 카톡 공유용 글 */
    fun shareText(t: Trip, spots: List<Spot>, ex: List<Expense>): String {
        val sb = StringBuilder()
        sb.append("✈️ ${t.title}\n${t.periodText}")
        if (t.companions.isNotBlank()) sb.append(" · ${t.companions}")
        if (t.rating > 0) sb.append("\n⭐ ${"%.1f".format(t.rating)}")
        if (t.tags.isNotEmpty()) sb.append("\n" + t.tags.joinToString(" ") { "#$it" })
        for (k in SpotKind.all) {
            val l = spots.filter { it.kind == k }.sortedBy { it.ts }
            if (l.isEmpty()) continue
            sb.append("\n\n${SpotKind.icon(k)} ${SpotKind.label(k)}")
            l.forEach { s ->
                sb.append("\n· ${s.title}")
                if (s.rating > 0) sb.append(" ★${"%.1f".format(s.rating)}")
                if (s.menu.isNotBlank()) sb.append(" · ${s.menu}")
                if (s.price > 0) sb.append(" · ${"%,d".format(s.price)}원")
                if (s.memo.isNotBlank()) sb.append("\n   ${s.memo.take(80)}")
            }
        }
        val sm = summarize(ex)
        if (sm.total > 0) sb.append("\n\n💸 경비 ${"%,d".format(sm.total)}원 (" + sm.byCat.entries.joinToString(", ") { "${it.key} ${"%,d".format(it.value)}" } + ")")
        if (t.memo.isNotBlank()) sb.append("\n\n📝 ${t.memo}")
        courseUrl(spots)?.let { sb.append("\n\n🗺 코스: $it") }
        return sb.toString()
    }

    /** 지도(웹 지도) 표시용 JSON: 장소 핀 + 자동 경로 선 */
    fun mapJson(spots: List<Spot>, track: List<TrackPoint>): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
        val p = spots.filter { it.hasLocation }.joinToString(",") {
            "{\"lat\":${it.lat},\"lon\":${it.lon},\"t\":\"${esc(SpotKind.icon(it.kind) + " " + it.title)}\",\"s\":\"${esc(it.ts.toLocalDate().toString() + (if (it.rating > 0) " ★" + it.rating else ""))}\"}"
        }
        val l = track.sortedBy { it.ts }.joinToString(",") { "[${it.lat},${it.lon}]" }
        return "{\"pins\":[$p],\"line\":[$l]}"
    }
}
