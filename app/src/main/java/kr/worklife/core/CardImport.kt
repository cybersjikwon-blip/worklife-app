package kr.worklife.core

import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.zip.ZipInputStream

/**
 * 카드 내역 가져오기 (문자 권한 없이)
 *  - 문자 공유/붙여넣기: 여러 문자를 한꺼번에 붙여넣어도 문자 단위로 나눠 해석
 *  - 카드사 이용내역 파일: .xlsx / .csv (헤더에서 날짜·가맹점·금액·카드·취소 열을 자동으로 찾음)
 */
object CardImport {

    /** 붙여넣은 글 → 문자 덩어리들 ([Web발신] / 빈 줄 / 'BC카드(' 시작 기준으로 나눔) */
    fun splitMessages(text: String): List<String> {
        val t = text.replace("\r", "")
        val starts = Regex("""(?m)^(?=\[\s*Web\s*발신\s*]|\[국외발신]|(?:BC|비씨|[가-힣A-Za-z]{1,10})\s*(?:카드)?\s*[(\[]\d[\d*]{2,3}[)\]])""")
        val byStart = t.split(starts).map { it.trim() }.filter { it.isNotEmpty() }
        val parts = if (byStart.size > 1) byStart else t.split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotEmpty() }
        return parts.flatMap { p -> if (p.contains("\n\n")) p.split(Regex("""\n\s*\n""")).map { it.trim() } else listOf(p) }.filter { it.isNotEmpty() }
    }

    fun parseText(text: String, now: LocalDateTime, keywords: List<String>, source: String = "share"): List<CardTx> =
        splitMessages(cleanOcr(text)).mapNotNull { m -> CardParser.parse("", m, now, keywords, source) }

    /** 화면 캡처 글자인식 결과 다듬기: '54,900 원' / '10 / 21' 같은 띄어쓰기, 메신저 화면 문구 제거 */
    fun cleanOcr(t: String): String = t.lines().map { l ->
        l.replace(Regex("""(\d)\s*,\s*(\d{3})"""), "$1,$2").replace(Regex("""(\d)\s+원"""), "$1원")
            .replace(Regex("""(\d{1,2})\s*/\s*(\d{1,2})\s+(\d{1,2})\s*:\s*(\d{2})"""), "$1/$2 $3:$4")
    }.filter { l -> !Regex("""확인된\s*발신번호|한눈에\s*보기|사용처\s*확인|입력할\s*수\s*없는|^\s*(오전|오후)\s*\d{1,2}:\d{2}\s*$""").containsMatchIn(l) }
        .joinToString("\n")

    // ───────── 표(엑셀/CSV) ─────────
    private val dateCol = Regex("이용\\s*일|승인\\s*일|거래\\s*일|사용\\s*일|일자|날짜|이용일시|승인일시")
    private val timeCol = Regex("시간|시각")
    private val merchantCol = Regex("가맹점|이용\\s*처|사용\\s*처|상호|내용")
    private val amountCol = Regex("이용\\s*금액|승인\\s*금액|거래\\s*금액|결제\\s*금액|금액")
    private val cardCol = Regex("카드\\s*번호|카드\\s*명|카드|이용\\s*카드")
    private val cancelCol = Regex("취소|구분|상태|승인\\s*구분")
    private val instCol = Regex("할부|개월")

    /** 행 목록 → 내역. 헤더 행은 '금액'이 들어간 첫 행 */
    fun fromRows(rows: List<List<String>>, keywordsBc: Boolean = true): List<CardTx> {
        val hi = rows.indexOfFirst { r -> r.any { amountCol.containsMatchIn(it) } && r.any { dateCol.containsMatchIn(it) } }
        if (hi < 0) return emptyList()
        val h = rows[hi].map { it.trim() }
        fun col(rx: Regex, avoid: Regex? = null) = h.indexOfFirst { rx.containsMatchIn(it) && (avoid == null || !avoid.containsMatchIn(it)) }
        val di = col(dateCol); val ti = col(timeCol, dateCol); val mi = col(merchantCol)
        val ai = h.indexOfFirst { Regex("이용\\s*금액|승인\\s*금액|거래\\s*금액|결제\\s*금액").containsMatchIn(it) }.let { if (it >= 0) it else col(amountCol, Regex("수수료|이자|포인트|할인|적립")) }
        val ci = col(cardCol, Regex("카드사"))
        val xi = col(cancelCol)
        val ii = col(instCol)
        val out = mutableListOf<CardTx>()
        for (r in rows.drop(hi + 1)) {
            fun cell(i: Int) = if (i in r.indices) r[i].trim() else ""
            val ts = parseDateTime(cell(di), cell(ti)) ?: continue
            val raw = cell(ai).replace(Regex("[^0-9.\\-]"), "")
            val amt = raw.toDoubleOrNull()?.toLong() ?: continue
            if (amt == 0L) continue
            val cancelled = Regex("취소|환불|매입취소").containsMatchIn(cell(xi)) || amt < 0
            val card = Regex("""(\d[\d*]{3})\D*$""").find(cell(ci))?.groupValues?.get(1) ?: ""
            val inst = Regex("""(\d{1,2})""").find(cell(ii))?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 1 } ?: 0
            val absAmt = kotlin.math.abs(amt)
            out += CardTx(ts = ts, amount = if (cancelled) -absAmt else absAmt, merchant = cell(mi).take(30), card = card,
                installment = inst, isBc = keywordsBc, source = "file",
                raw = "파일 ${ts} ${cell(mi).take(20)} $absAmt ${if (cancelled) "취소" else ""}")
        }
        return out
    }

    /** 2026.10.21 / 2026-10-21 14:23 / 26/10/21 / 10/21 / 20261021 / 엑셀 날짜 숫자(45951.6) */
    fun parseDateTime(d: String, t: String = "", today: LocalDate = LocalDate.now()): LocalDateTime? {
        val s = d.trim()
        if (s.isEmpty()) return null
        s.toDoubleOrNull()?.let { v ->
            if (v > 20000 && v < 80000) {
                val days = v.toLong(); val frac = v - days
                val dt = LocalDate.of(1899, 12, 30).plusDays(days).atStartOfDay().plusSeconds((frac * 86400).toLong())
                return withTime(dt, t)
            }
        }
        val m1 = Regex("""(\d{4})[.\-/년\s]+(\d{1,2})[.\-/월\s]+(\d{1,2})""").find(s)
        val m2 = Regex("""^(\d{4})(\d{2})(\d{2})""").find(s)
        val m3 = Regex("""(\d{2})[.\-/](\d{1,2})[.\-/](\d{1,2})""").find(s)
        val m4 = Regex("""(\d{1,2})[.\-/](\d{1,2})""").find(s)
        val date = runCatching {
            when {
                m1 != null -> LocalDate.of(m1.groupValues[1].toInt(), m1.groupValues[2].toInt(), m1.groupValues[3].toInt())
                m2 != null -> LocalDate.of(m2.groupValues[1].toInt(), m2.groupValues[2].toInt(), m2.groupValues[3].toInt())
                m3 != null -> LocalDate.of(2000 + m3.groupValues[1].toInt(), m3.groupValues[2].toInt(), m3.groupValues[3].toInt())
                m4 != null -> LocalDate.of(today.year, m4.groupValues[1].toInt(), m4.groupValues[2].toInt()).let { if (it.isAfter(today.plusDays(1))) it.minusYears(1) else it }
                else -> null
            }
        }.getOrNull() ?: return null
        // 시간: 같은 칸(2026.10.21 14:23) 또는 시간 칸
        val tm = Regex("""(\d{1,2}):(\d{2})""").find(s.substring(minOf(s.length, (m1 ?: m3 ?: m4)?.range?.last?.plus(1) ?: 0))) ?: Regex("""(\d{1,2}):(\d{2})""").find(t)
        return if (tm != null) runCatching { date.atTime(tm.groupValues[1].toInt(), tm.groupValues[2].toInt()) }.getOrNull() ?: date.atTime(12, 0)
        else date.atTime(12, 0)
    }

    private fun withTime(dt: LocalDateTime, t: String): LocalDateTime {
        val tm = Regex("""(\d{1,2}):(\d{2})""").find(t) ?: return if (dt.toLocalTime() == LocalTime.MIDNIGHT) dt.withHour(12) else dt
        return dt.toLocalDate().atTime(tm.groupValues[1].toInt().coerceIn(0, 23), tm.groupValues[2].toInt().coerceIn(0, 59))
    }

    // ───────── CSV ─────────
    fun csvRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val delim = if (text.lineSequence().take(10).sumOf { it.count { c -> c == '\t' } } > text.lineSequence().take(10).sumOf { it.count { c -> c == ',' } } / 2) '\t' else ','
        var cur = mutableListOf<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                q && ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> q = !q
                !q && ch == delim -> { cur.add(sb.toString()); sb.clear() }
                !q && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    cur.add(sb.toString()); sb.clear(); rows.add(cur); cur = mutableListOf()
                }
                else -> sb.append(ch)
            }
            i++
        }
        if (sb.isNotEmpty() || cur.isNotEmpty()) { cur.add(sb.toString()); rows.add(cur) }
        return rows
    }

    // ───────── XLSX (zip 안의 XML을 직접 읽음: 외부 라이브러리 없이) ─────────
    fun xlsxRows(bytes: ByteArray): List<List<String>> {
        val files = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == "xl/sharedStrings.xml" || e.name.startsWith("xl/worksheets/sheet")) files[e.name] = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        val shared = files["xl/sharedStrings.xml"]?.let { x ->
            Regex("""<si>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL).findAll(x).map { si ->
                Regex("""<t[^>]*>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL).findAll(si.groupValues[1]).joinToString("") { unxml(it.groupValues[1]) }
            }.toList()
        } ?: emptyList()
        val sheet = files["xl/worksheets/sheet1.xml"] ?: files.entries.filter { it.key.startsWith("xl/worksheets/sheet") }.minByOrNull { it.key }?.value ?: return emptyList()
        val rows = mutableListOf<List<String>>()
        for (rm in Regex("""<row[^>]*>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL).findAll(sheet)) {
            val cells = sortedMapOf<Int, String>()
            for (cm in Regex("""<c\s([^>]*?)(?:/>|>(.*?)</c>)""", RegexOption.DOT_MATCHES_ALL).findAll(rm.groupValues[1])) {
                val attrs = cm.groupValues[1]; val inner = cm.groupValues[2]
                val ref = Regex("""r="([A-Z]+)\d+"""").find(attrs)?.groupValues?.get(1) ?: continue
                val type = Regex("""t="(\w+)"""").find(attrs)?.groupValues?.get(1) ?: ""
                val v = Regex("""<v>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL).find(inner)?.groupValues?.get(1) ?: ""
                val value = when (type) {
                    "s" -> v.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                    "inlineStr" -> Regex("""<t[^>]*>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL).findAll(inner).joinToString("") { unxml(it.groupValues[1]) }
                    else -> unxml(v)
                }
                cells[colIndex(ref)] = value
            }
            if (cells.isEmpty()) continue
            val n = cells.lastKey() + 1
            rows += (0 until n).map { cells[it] ?: "" }
        }
        return rows
    }

    private fun colIndex(ref: String) = ref.fold(0) { a, ch -> a * 26 + (ch - 'A' + 1) } - 1
    private fun unxml(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** 파일 종류 자동 판별 (xlsx = zip, 아니면 CSV/TSV 글자. 한글 엑셀 CSV는 EUC-KR일 수 있음) */
    fun rowsFromFile(bytes: ByteArray): List<List<String>> {
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) return xlsxRows(bytes)
        val utf = bytes.toString(Charsets.UTF_8).removePrefix("﻿")
        val text = if (utf.contains('�')) runCatching { String(bytes, charset("EUC-KR")) }.getOrDefault(utf) else utf
        // 옛 엑셀(.xls)은 HTML 표로 내려주는 카드사가 많음 → <td> 읽기
        if (text.contains("<table", ignoreCase = true)) return htmlRows(text)
        return csvRows(text)
    }

    fun htmlRows(html: String): List<List<String>> =
        Regex("""<tr[^>]*>(.*?)</tr>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(html).map { tr ->
            Regex("""<t[dh][^>]*>(.*?)</t[dh]>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(tr.groupValues[1])
                .map { unxml(it.groupValues[1].replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").trim()) }.toList()
        }.filter { it.isNotEmpty() }.toList()
}
