package com.krish.spendsense.domain

import com.krish.spendsense.data.Txn
import java.time.LocalDate

/**
 * Visual spending band. Boundaries: below 50% healthy; 50% up to the user's configured warning
 * percentage (Settings -> Budgets, default 80%) approaching; from there to 100% near; at or over
 * the limit over. Without a limit the state is always healthy.
 */
enum class SpendBand { HEALTHY, APPROACHING, NEAR, OVER }

/**
 * Today's spending against the applicable daily limit: the one calculation shared by the app theme,
 * Home, notifications and the widget. Spend comes from [Totals.spend] (same rules as the dashboard:
 * debits minus linked refunds, excluded rows and accounts left out).
 */
data class SpendState(val spentPaise: Long, val limitPaise: Long, val warnPct: Int) {
    val hasLimit: Boolean get() = limitPaise > 0

    /** Whole percent of the limit used (0 without a limit), capped to stay printable. */
    val percent: Int
        get() = if (!hasLimit || spentPaise <= 0) 0 else (spentPaise * 100 / limitPaise).coerceAtMost(10_000).toInt()

    /** Fraction for progress rings, 0 without a limit. */
    val fraction: Float get() = if (!hasLimit) 0f else spentPaise.toFloat() / limitPaise

    val remainingPaise: Long get() = limitPaise - spentPaise

    val band: SpendBand
        get() = when {
            !hasLimit -> SpendBand.HEALTHY
            spentPaise >= limitPaise -> SpendBand.OVER
            percent >= warnPct.coerceIn(1, 99) -> SpendBand.NEAR
            percent >= HEALTHY_BELOW -> SpendBand.APPROACHING
            else -> SpendBand.HEALTHY
        }

    companion object {
        const val HEALTHY_BELOW = 50

        /**
         * [rows] may hold more than [day]; only that IST day counts, so a new day starts at zero
         * even if yesterday's rows are still in memory.
         */
        fun of(rows: List<Txn>, day: LocalDate, limitPaise: Long, warnPct: Int, excludedAccounts: Set<Long> = emptySet()) =
            SpendState(Totals.spend(rows.filter { Dates.date(it.timestamp) == day }, excludedAccounts), limitPaise, warnPct)
    }
}
