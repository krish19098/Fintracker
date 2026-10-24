package com.krish.spendsense.domain

import com.krish.spendsense.data.Category
import com.krish.spendsense.data.Txn
import java.time.LocalDate
import java.time.YearMonth

data class LimitAlert(
    /** Every fired-key this alert covers; the caller stores them so each level fires once. */
    val keys: List<String>,
    val level: Int,
    val title: String,
    val text: String,
)

/** Pure alert decisions; posting notifications happens in the ingest layer. */
object AlertEngine {
    private fun crossed(spent: Long, limit: Long, warnPct: Int): Int? = when {
        limit <= 0 -> null
        spent >= limit -> 100
        spent * 100 >= limit * warnPct -> warnPct
        else -> null
    }

    private fun levelAlert(prefix: String, spent: Long, limit: Long, warnPct: Int, fired: Set<String>): Pair<Int, List<String>>? {
        val level = crossed(spent, limit, warnPct) ?: return null
        val keys = listOf(warnPct, 100).filter { it <= level }.map { "$prefix:$it" }
        if (keys.last() in fired) return null
        return level to keys
    }

    /**
     * Daily limit: warn at [warnPct]% and alert at 100%, each once per day. [scopeKey]/[scopeLabel]
     * identify a per-account limit (null = the global limit).
     */
    fun dailyLimit(
        spentToday: Long, limit: Long, warnPct: Int, fired: Set<String>, day: LocalDate,
        scopeKey: String? = null, scopeLabel: String? = null,
    ): LimitAlert? {
        val prefix = if (scopeKey == null) "daily:$day" else "daily:$scopeKey:$day"
        val (level, keys) = levelAlert(prefix, spentToday, limit, warnPct, fired) ?: return null
        val who = scopeLabel?.let { "$it: " } ?: ""
        return if (level >= 100) {
            LimitAlert(keys, level, "${who}Daily limit reached", "${Money.short(spentToday)} spent today · limit ${Money.short(limit)}")
        } else {
            LimitAlert(
                keys, level, "$who$level% of today's limit used",
                "${Money.short(spentToday)} of ${Money.short(limit)} · ${Money.short(limit - spentToday)} left",
            )
        }
    }

    /** Category monthly budget: 80% and 100%, each once per month. */
    fun categoryBudget(category: Category, spentMonth: Long, warnPct: Int, fired: Set<String>, month: YearMonth): LimitAlert? {
        val budget = category.monthlyBudgetPaise ?: return null
        val (level, keys) = levelAlert("cat:${category.id}:$month", spentMonth, budget, warnPct, fired) ?: return null
        val title = if (level >= 100) "${category.name} budget exceeded" else "${category.name} at $level% of budget"
        return LimitAlert(keys, level, title, "${Money.short(spentMonth)} of ${Money.short(budget)} this month")
    }

    fun isLarge(t: Txn, thresholdPaise: Long): Boolean =
        t.direction == com.krish.spendsense.parse.Direction.DEBIT && thresholdPaise > 0 && t.amountPaise >= thresholdPaise

    /** "₹250 → Ravi Tea Stall · Today ₹1,840 / ₹2,000" */
    fun txnText(t: Txn, spentToday: Long, dailyLimit: Long): String =
        "${Money.format(t.amountPaise)} → ${t.displayPayee} · Today ${Money.short(spentToday)} / ${Money.short(dailyLimit)}"

    /** "₹2,340 today · 60% Food · biggest: Swiggy ₹620"; null when nothing was spent. */
    fun daySummary(todays: List<Txn>, categories: Map<Long, Category>, excludedAccounts: Set<Long> = emptySet()): String? {
        val total = Totals.spend(todays, excludedAccounts)
        if (total <= 0) return null
        val parts = mutableListOf("${Money.short(total)} today")
        Totals.byCategory(todays, excludedAccounts).maxByOrNull { it.value }?.let { (cat, amt) ->
            parts += "${amt * 100 / total}% ${cat?.let { categories[it]?.name } ?: "Uncategorised"}"
        }
        todays.filter { Totals.spendDelta(it, excludedAccounts) > 0 }.maxByOrNull { it.amountPaise }?.let {
            parts += "biggest: ${it.displayPayee} ${Money.short(it.amountPaise)}"
        }
        return parts.joinToString(" · ")
    }

    /** Weekly / monthly digest line, e.g. label "last week", prevLabel "the week before". */
    fun digest(label: String, prevLabel: String, txns: List<Txn>, previous: List<Txn>, categories: Map<Long, Category>, excludedAccounts: Set<Long> = emptySet()): String {
        val total = Totals.spend(txns, excludedAccounts)
        val prev = Totals.spend(previous, excludedAccounts)
        val top = Totals.byCategory(txns, excludedAccounts).maxByOrNull { it.value }
            ?.let { (cat, amt) -> " · top: ${cat?.let { categories[it]?.name } ?: "Uncategorised"} ${Money.short(amt)}" } ?: ""
        val change = when {
            prev <= 0 -> ""
            total >= prev -> " · ↑ ${(total - prev) * 100 / prev}% vs $prevLabel"
            else -> " · ↓ ${(prev - total) * 100 / prev}% vs $prevLabel"
        }
        return "${Money.short(total)} spent $label$change$top"
    }
}
