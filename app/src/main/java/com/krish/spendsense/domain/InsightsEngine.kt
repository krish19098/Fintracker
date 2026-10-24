package com.krish.spendsense.domain

import com.krish.spendsense.data.Category
import com.krish.spendsense.data.CategoryKind
import com.krish.spendsense.data.Txn
import com.krish.spendsense.parse.Direction
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

sealed interface InsightAction {
    data class SetBudget(val categoryId: Long, val paise: Long) : InsightAction
    data object OpenSubscriptions : InsightAction
}

data class Insight(
    val kind: String,
    val title: String,
    val text: String,
    val action: InsightAction? = null,
    val actionLabel: String? = null,
)

data class Recurring(
    val payee: String,
    val payeeKey: String,
    val amountPaise: Long,
    val periodDays: Int,
    val count: Int,
    val lastAt: Long,
    val nextExpected: LocalDate,
) {
    /** Monthly equivalent: weekly x 52 / 12. */
    val monthlyPaise: Long get() = if (periodDays <= 8) amountPaise * 52 / 12 else amountPaise
}

data class Forecast(
    val monthToDate: Long,
    val projected: Long,
    val budget: Long,
    /** Day of month the budget is expected to be crossed; null if not on pace to exceed. */
    val exceedDay: Int?,
    val alreadyOver: Boolean,
)

enum class TimeBand(val label: String, val range: String) {
    MORNING("Mornings", "5 am–12 pm"),
    AFTERNOON("Afternoons", "12–5 pm"),
    EVENING("Evenings", "5–10 pm"),
    LATE_NIGHT("Late nights", "10 pm–5 am");

    companion object {
        fun of(hour: Int) = when (hour) {
            in 5..11 -> MORNING
            in 12..16 -> AFTERNOON
            in 17..21 -> EVENING
            else -> LATE_NIGHT
        }
    }
}

/** Rules-only, on-device insights. All money math is integer paise. */
class InsightsEngine(categories: List<Category>, private val excludedAccounts: Set<Long> = emptySet()) {
    private val cats = categories.associateBy { it.id }

    private fun catName(id: Long?) = id?.let { cats[it]?.name } ?: "Uncategorised"
    private fun month(t: Txn) = YearMonth.from(Dates.date(t.timestamp))
    private fun inMonth(txns: List<Txn>, m: YearMonth) = txns.filter { month(it) == m }
    private fun spendRows(txns: List<Txn>) = txns.filter { Totals.spendDelta(it, excludedAccounts) > 0 }
    private fun payeeKey(t: Txn) = Categorizer.payeeKey(t.payeeName, t.payeeVpa) ?: t.counterpartyTail?.lowercase()

    fun all(txns: List<Txn>, today: LocalDate, dailyLimit: Long, monthlyBudget: Long): List<Insight> = buildList {
        add(forecastInsight(forecast(txns, today, monthlyBudget)))
        suggestion(txns, today)?.let(::add)
        addAll(categoryChange(txns, today))
        addAll(frequency(txns, today))
        smallLeaks(txns, today)?.let(::add)
        weekendVsWeekday(txns, today)?.let(::add)
        timeOfDay(txns, today)?.let(::add)
        streak(txns, today, dailyLimit).takeIf { it >= 2 }?.let {
            add(Insight("streak", "$it days under your daily limit 🎉", "Keep it going — limit ${Money.short(dailyLimit)} a day"))
        }
        recurring(txns, today).takeIf { it.isNotEmpty() }?.let { list ->
            add(
                Insight(
                    "recurring", "Subscriptions & recurring: ${Money.short(list.sumOf { it.monthlyPaise })}/mo",
                    "${list.size} repeating payments found", InsightAction.OpenSubscriptions, "View",
                ),
            )
        }
    }.filter { it.title.isNotEmpty() }

    /** Category spend this month vs last month; flagged at ±30% and ±₹500. */
    fun categoryChange(txns: List<Txn>, today: LocalDate): List<Insight> {
        val m = YearMonth.from(today)
        val cur = Totals.byCategory(inMonth(txns, m), excludedAccounts)
        val prev = Totals.byCategory(inMonth(txns, m.minusMonths(1)), excludedAccounts)
        return (cur.keys + prev.keys).mapNotNull { cat ->
            val c = cur[cat] ?: 0
            val p = prev[cat] ?: 0
            val diff = kotlin.math.abs(c - p)
            if (p <= 0 || diff < 50_000 || diff * 100 < p * 30) return@mapNotNull null
            val change = when {
                c >= 2 * p -> {
                    val tenths = c * 10 / p
                    "↑ ${tenths / 10}${if (tenths % 10 != 0L) ".${tenths % 10}" else ""}×"
                }
                c > p -> "↑ ${diff * 100 / p}%"
                else -> "↓ ${diff * 100 / p}%"
            }
            diff to Insight(
                "category_change", "${catName(cat)} $change — ${Money.short(c)} vs ${Money.short(p)}",
                "This month so far vs last month",
            )
        }.sortedByDescending { it.first }.map { it.second }
    }

    /** "Swiggy 14 times this month (₹3,860)" for payees with 8+ payments. */
    fun frequency(txns: List<Txn>, today: LocalDate): List<Insight> =
        spendRows(inMonth(txns, YearMonth.from(today))).groupBy { payeeKey(it) }
            .filter { (k, v) -> k != null && v.size >= 8 }
            .entries.sortedByDescending { it.value.size }
            .map { (_, v) ->
                val name = v.maxBy { it.timestamp }.displayPayee
                Insight("frequency", "$name ${v.size} times this month (${Money.short(v.sumOf { it.amountPaise })})", "One of your most frequent payees")
            }

    /** Sum of debits under ₹100 this month, shown from ₹1,000. */
    fun smallLeaks(txns: List<Txn>, today: LocalDate): Insight? {
        val small = spendRows(inMonth(txns, YearMonth.from(today))).filter { it.amountPaise < 10_000 }
        val sum = small.sumOf { it.amountPaise }
        if (sum < 100_000) return null
        return Insight("small_leaks", "Small leaks: ${Money.short(sum)}", "${small.size} payments under ₹100 this month")
    }

    /** Average daily spend on weekend vs weekday days over the last 4 weeks; shown at ≥25% difference. */
    fun weekendVsWeekday(txns: List<Txn>, today: LocalDate): Insight? {
        val from = today.minusDays(27)
        val days = (0L..27L).map { from.plusDays(it) }
        val isWeekend = { d: LocalDate -> d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY }
        val window = txns.filter { Dates.date(it.timestamp) in from..today }
        val weekendDays = days.count(isWeekend)
        val weekdayDays = days.size - weekendDays
        val weekend = window.filter { isWeekend(Dates.date(it.timestamp)) }.sumOf { Totals.spendDelta(it, excludedAccounts) }
        val weekday = window.filterNot { isWeekend(Dates.date(it.timestamp)) }.sumOf { Totals.spendDelta(it, excludedAccounts) }
        val avgWeekend = weekend / weekendDays
        val avgWeekday = weekday / weekdayDays
        val hi = maxOf(avgWeekend, avgWeekday)
        val lo = minOf(avgWeekend, avgWeekday)
        if (hi <= 0 || (hi - lo) * 100 < lo * 25) return null
        val title = if (avgWeekend > avgWeekday) "Weekends cost more" else "Weekdays cost more"
        return Insight("weekend", title, "${Money.short(avgWeekend)}/day on weekends vs ${Money.short(avgWeekday)}/day on weekdays (last 4 weeks)")
    }

    /** The most expensive time band this month. */
    fun timeOfDay(txns: List<Txn>, today: LocalDate): Insight? {
        val rows = spendRows(inMonth(txns, YearMonth.from(today)))
        val total = rows.sumOf { it.amountPaise }
        if (total <= 0) return null
        val (band, amt) = rows.groupBy { TimeBand.of(Dates.zoned(it.timestamp).hour) }
            .mapValues { (_, v) -> v.sumOf { it.amountPaise } }.maxBy { it.value }
        return Insight("time_of_day", "${band.label} cost you the most", "${Money.short(amt)} (${amt * 100 / total}%) this month between ${band.range}")
    }

    /**
     * Same payee, similar amount (±10%), at 25–35 or 6–8 day intervals, seen at least twice.
     * Chains that stopped more than two periods ago are dropped.
     */
    fun recurring(txns: List<Txn>, today: LocalDate): List<Recurring> =
        txns.filter { it.direction == Direction.DEBIT && it.excludedReason == null }
            .groupBy { payeeKey(it) }
            .mapNotNull { (key, rows) ->
                if (key == null || rows.size < 2) return@mapNotNull null
                val sorted = rows.sortedBy { it.timestamp }
                val latest = sorted.last()
                val similar = sorted.filter { kotlin.math.abs(it.amountPaise - latest.amountPaise) * 10 <= latest.amountPaise }
                val chain = mutableListOf(latest)
                var weekly: Boolean? = null
                for (prev in similar.dropLast(1).asReversed()) {
                    val gap = ChronoUnit.DAYS.between(Dates.date(prev.timestamp), Dates.date(chain.last().timestamp))
                    val isWeekly = when (gap) {
                        in 6..8 -> true
                        in 25..35 -> false
                        else -> break
                    }
                    if (weekly != null && weekly != isWeekly) break
                    weekly = isWeekly
                    chain += prev
                }
                if (chain.size < 2) return@mapNotNull null
                val span = ChronoUnit.DAYS.between(Dates.date(chain.last().timestamp), Dates.date(latest.timestamp))
                val period = (span / (chain.size - 1)).toInt()
                val next = Dates.date(latest.timestamp).plusDays(period.toLong())
                if (next.plusDays(period * 2L) < today) return@mapNotNull null
                Recurring(latest.displayPayee, key, latest.amountPaise, period, chain.size, latest.timestamp, next)
            }.sortedByDescending { it.monthlyPaise }

    /** Month-to-date ÷ elapsed days × days in month, vs the monthly budget. */
    fun forecast(txns: List<Txn>, today: LocalDate, budget: Long): Forecast {
        val mtd = Totals.spend(inMonth(txns, YearMonth.from(today)).filter { Dates.date(it.timestamp) <= today }, excludedAccounts)
        val elapsed = today.dayOfMonth.toLong()
        val dim = today.lengthOfMonth().toLong()
        val projected = mtd * dim / elapsed
        val alreadyOver = budget in 1..mtd
        val exceedDay = if (!alreadyOver && budget > 0 && projected > budget && mtd > 0) {
            ((budget * elapsed + mtd - 1) / mtd).toInt().coerceAtMost(dim.toInt())
        } else null
        return Forecast(mtd, projected, budget, exceedDay, alreadyOver)
    }

    fun forecastInsight(f: Forecast): Insight = when {
        f.budget <= 0 -> Insight("forecast", "On pace for ${Money.short(f.projected)} this month", "Based on ${Money.short(f.monthToDate)} so far")
        f.alreadyOver -> Insight("forecast", "Over budget by ${Money.short(f.monthToDate - f.budget)}", "${Money.short(f.monthToDate)} spent vs ${Money.short(f.budget)} budget")
        f.exceedDay != null -> Insight(
            "forecast",
            "On pace for ${Money.short(f.projected)} vs ${Money.short(f.budget)} budget — exceeds around the ${ordinal(f.exceedDay)}.",
            "Based on ${Money.short(f.monthToDate)} so far",
        )
        else -> Insight(
            "forecast", "On pace for ${Money.short(f.projected)} — ${Money.short(f.budget - f.projected)} under budget",
            "Monthly budget ${Money.short(f.budget)}",
        )
    }

    /** For the category furthest over its 3-month median, suggest capping it at that median. */
    fun suggestion(txns: List<Txn>, today: LocalDate): Insight? {
        val m = YearMonth.from(today)
        val elapsed = today.dayOfMonth.toLong()
        val dim = today.lengthOfMonth().toLong()
        val history = (1L..3L).map { Totals.byCategory(inMonth(txns, m.minusMonths(it)), excludedAccounts) }
        val cur = Totals.byCategory(inMonth(txns, m), excludedAccounts)
        val best = cur.mapNotNull { (cat, amt) ->
            val c = cat?.let { cats[it] } ?: return@mapNotNull null
            if (c.kind != CategoryKind.SPEND || c.monthlyBudgetPaise != null) return@mapNotNull null
            val median = history.map { it[cat] ?: 0L }.sorted()[1]
            val projected = amt * dim / elapsed
            if (median <= 0 || projected <= median) null else Triple(c, median, projected)
        }.maxByOrNull { it.third - it.second } ?: return null
        val (c, median, projected) = best
        val cap = (median + 5_000) / 10_000 * 10_000
        // "~" saving, rounded to ₹100.
        val saving = (projected - cap + 5_000) / 10_000 * 10_000
        if (cap <= 0 || saving < 10_000) return null
        return Insight(
            "suggestion", "Cap ${c.name} at ${Money.short(cap)} → save ~${Money.short(saving)}/mo",
            "${c.name} is above its 3-month median of ${Money.short(median)}",
            InsightAction.SetBudget(c.id, cap), "Set budget",
        )
    }

    /** Consecutive completed days (yesterday backwards) at or under the daily limit. */
    fun streak(txns: List<Txn>, today: LocalDate, dailyLimit: Long): Int {
        if (txns.isEmpty() || dailyLimit <= 0) return 0
        val first = txns.minOf { Dates.date(it.timestamp) }
        val perDay = txns.groupBy { Dates.date(it.timestamp) }.mapValues { Totals.spend(it.value, excludedAccounts) }
        var d = today.minusDays(1)
        var n = 0
        while (d >= first && (perDay[d] ?: 0) <= dailyLimit) {
            n++
            d = d.minusDays(1)
        }
        return n
    }

    fun incomeVsSpend(txns: List<Txn>, today: LocalDate): Insight {
        val rows = inMonth(txns, YearMonth.from(today))
        val income = Totals.income(rows, excludedAccounts)
        val spend = Totals.spend(rows, excludedAccounts)
        val text = if (income >= spend) "You kept ${Money.short(income - spend)} this month" else "Spent ${Money.short(spend - income)} more than came in"
        return Insight("income_vs_spend", "Income ${Money.short(income)} vs Spend ${Money.short(spend)}", text)
    }

    companion object {
        fun ordinal(n: Int): String {
            val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
                1 -> "st"
                2 -> "nd"
                3 -> "rd"
                else -> "th"
            }
            return "$n$suffix"
        }
    }
}
