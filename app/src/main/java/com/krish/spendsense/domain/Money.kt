package com.krish.spendsense.domain

import com.krish.spendsense.parse.ParseUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Indian currency formatting (₹1,23,456) on integer paise. */
object Money {
    fun group(n: Long): String {
        val s = n.toString()
        if (s.length <= 3) return s
        val head = s.dropLast(3)
        val pairs = head.reversed().chunked(2).joinToString(",").reversed()
        return "$pairs,${s.takeLast(3)}"
    }

    /** Paise are shown only when non-zero, or always with [forcePaise]. */
    fun format(paise: Long, forcePaise: Boolean = false): String {
        val sign = if (paise < 0) "-" else ""
        val abs = kotlin.math.abs(paise)
        val rupees = group(abs / 100)
        val p = abs % 100
        return if (p != 0L || forcePaise) "$sign₹$rupees.${p.toString().padStart(2, '0')}" else "$sign₹$rupees"
    }

    /** Rupee-rounded format for summaries and insights. */
    fun short(paise: Long): String = format((paise + 50) / 100 * 100)

    /** User input such as "1,250.50" -> paise. */
    fun parse(input: String): Long? = ParseUtils.parseAmountPaise(input.replace("₹", "").trim())
}

object Dates {
    private val dayFmt = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val fullFmt = DateTimeFormatter.ofPattern("EEE, d MMM yyyy · h:mm a", Locale.ENGLISH)

    fun zoned(ts: Long): ZonedDateTime = Instant.ofEpochMilli(ts).atZone(ParseUtils.IST)
    fun date(ts: Long): LocalDate = zoned(ts).toLocalDate()

    /** "Fri, 23 Oct" */
    fun day(ts: Long): String = dayFmt.format(zoned(ts))
    fun day(d: LocalDate): String = dayFmt.format(d)
    fun time(ts: Long): String = timeFmt.format(zoned(ts))
    fun full(ts: Long): String = fullFmt.format(zoned(ts))

    fun startOf(d: LocalDate): Long = d.atStartOfDay(ParseUtils.IST).toInstant().toEpochMilli()
    fun endOf(d: LocalDate): Long = startOf(d.plusDays(1)) - 1
}
