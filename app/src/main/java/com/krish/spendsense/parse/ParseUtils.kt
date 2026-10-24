package com.krish.spendsense.parse

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

object ParseUtils {
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    /** Debug-build hook (set from the app); never receives message bodies. */
    @Volatile
    var debugLog: ((String) -> Unit)? = null

    /** Amount with a currency marker; group 1 is the number. */
    const val AMT = """(?:Rs\.?|INR|₹)\s*([\d,]+(?:\.\d{1,2})?)"""

    /** Any of the accepted date formats; group 1 is the whole date. */
    const val DATE = """(\d{1,2}[-/](?:\d{1,2}|[A-Za-z]{3})[-/]\d{2,4})"""
    const val TIME = """(\d{1,2}:\d{2}(?::\d{2})?)"""
    const val VPA = """[\w.\-]+@[\w]+"""

    private val vpaFull = Regex("^$VPA$")
    private val dateParts = Regex("""^(\d{1,2})[-/](\d{1,2}|[A-Za-z]{3})[-/](\d{2,4})$""")
    private val months = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    /** Collapses horizontal whitespace, trims lines and removes spaces after '/' so regexes stay simple. */
    fun normalize(body: String): String = body
        .replace("\r\n", "\n").replace('\r', '\n')
        .replace(Regex("[\\t\\u00A0\\u2007\\u202F ]+"), " ")
        .lines().joinToString("\n") { it.trim() }
        .replace(Regex("""/\s+"""), "/")
        .trim()

    /** "1,23,456.7" -> 12345670. Integer-only arithmetic; never floating point. */
    fun parseAmountPaise(raw: String): Long? {
        val s = raw.replace(",", "").trim()
        if (s.isEmpty()) return null
        val parts = s.split('.')
        // 15 rupee digits (₹ 100 lakh crore) is far beyond any real amount and keeps rupees * 100 from overflowing.
        if (parts.size > 2 || parts[0].length > 15) return null
        val rupees = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
        val fracRaw = parts.getOrNull(1) ?: ""
        if (fracRaw.length > 2 || !fracRaw.all { it.isDigit() }) return null
        val paise = fracRaw.padEnd(2, '0').toLong()
        return rupees * 100 + paise
    }

    fun isVpa(s: String): Boolean = vpaFull.matches(s.trim())

    /** Accepts dd-MM-yy, dd-MM-yyyy, dd/MM/yy, dd/MM/yyyy, dd-MMM-yy, dd-MMM-yyyy (any case). */
    fun parseDate(raw: String): LocalDate? {
        val m = dateParts.matchEntire(raw.trim()) ?: return null
        val (d, mo, y) = m.destructured
        val month = mo.toIntOrNull() ?: (months.indexOf(mo.uppercase()) + 1).takeIf { it > 0 } ?: return null
        val year = when (y.length) {
            2 -> 2000 + y.toInt()
            4 -> y.toInt()
            else -> return null
        }
        return runCatching { LocalDate.of(year, month, d.toInt()) }.getOrNull()
    }

    fun parseTime(raw: String): LocalTime? {
        val p = raw.trim().split(':').map { it.toIntOrNull() ?: return null }
        return runCatching { LocalTime.of(p[0], p[1], p.getOrElse(2) { 0 }) }.getOrNull()
    }

    fun istDate(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(IST).toLocalDate()

    /**
     * Date of a UPI RRN, whose first digit is the year's last digit and next three the day of year
     * (e.g. 626017540942 -> 2026 day 260). Returns null when the pattern does not fit.
     */
    fun rrnDate(ref: String?, receivedAt: Long): LocalDate? {
        if (ref == null || ref.length != 12 || !ref.all { it.isDigit() }) return null
        val yDigit = ref[0] - '0'
        val day = ref.substring(1, 4).toInt()
        val receivedYear = istDate(receivedAt).year
        val year = receivedYear - ((receivedYear % 10 - yDigit + 10) % 10)
        if (day < 1 || day > LocalDate.of(year, 1, 1).lengthOfYear()) return null
        return LocalDate.ofYearDay(year, day)
    }

    /**
     * Applies the timestamp rules: SMS date+time wins; a date alone uses the received time when the
     * received date matches, else noon IST. A missing date falls back to the RRN date (soft check),
     * then to the received time.
     */
    fun resolveTimestamp(date: LocalDate?, time: LocalTime?, receivedAt: Long, upiRef: String?): Long {
        val rrn = rrnDate(upiRef, receivedAt)
        if (date != null && rrn != null && rrn != date) {
            debugLog?.invoke("RRN date $rrn differs from SMS date $date")
        }
        val d = date ?: rrn ?: return receivedAt
        if (time != null) return ZonedDateTime.of(d, time, IST).toInstant().toEpochMilli()
        return if (istDate(receivedAt) == d) receivedAt
        else ZonedDateTime.of(d, LocalTime.NOON, IST).toInstant().toEpochMilli()
    }

    fun cleanName(raw: String?): String? = raw?.trim()?.trimEnd('.', ',')?.trim()?.takeIf { it.isNotEmpty() }
}
