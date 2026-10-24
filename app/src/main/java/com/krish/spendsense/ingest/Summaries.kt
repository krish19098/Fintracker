package com.krish.spendsense.ingest

import com.krish.spendsense.AppContainer
import com.krish.spendsense.domain.AlertEngine
import com.krish.spendsense.domain.Dates
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

object Summaries {
    suspend fun post(c: AppContainer, kind: Int) {
        val repo = c.repository
        val cats = repo.allCategories().associateBy { it.id }
        val excluded = repo.excludedAccountIds()
        val today = LocalDate.now(com.krish.spendsense.parse.ParseUtils.IST)
        suspend fun rows(from: LocalDate, to: LocalDate) = repo.range(Dates.startOf(from), Dates.endOf(to))
        when (kind) {
            SummaryWorker.WEEKLY -> {
                val text = AlertEngine.digest("this week", "last week", rows(today.minusDays(6), today), rows(today.minusDays(13), today.minusDays(7)), cats, excluded)
                c.notifier.postSummary(kind, "Weekly digest", text, openInsights = true)
            }
            SummaryWorker.MONTHLY -> {
                val m = YearMonth.from(today).minusMonths(1)
                val name = m.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                val text = AlertEngine.digest(
                    "in $name", m.minusMonths(1).month.getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                    rows(m.atDay(1), m.atEndOfMonth()), rows(m.minusMonths(1).atDay(1), m.minusMonths(1).atEndOfMonth()), cats, excluded,
                )
                c.notifier.postSummary(kind, "$name digest", text, openInsights = true)
            }
            else -> AlertEngine.daySummary(repo.dayTxns(today), cats, excluded)?.let {
                c.notifier.postSummary(kind, "Today's spending", it, openInsights = false)
            }
        }
    }
}
