package com.krish.spendsense.domain

import com.krish.spendsense.domain.TestData.TODAY
import com.krish.spendsense.domain.TestData.txn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class InsightsEngineTest {
    private val engine = InsightsEngine(TestData.categories)
    private val oct = { d: Int -> LocalDate.of(2026, 10, d) }
    private val sep = { d: Int -> LocalDate.of(2026, 9, d) }

    @Test
    fun categoryChangeFlagsDoubling() {
        val rows = listOf(txn(4200, date = oct(5), categoryId = TestData.FOOD), txn(2100, date = sep(5), categoryId = TestData.FOOD))
        assertEquals("Food ↑ 2× — ₹4,200 vs ₹2,100", engine.categoryChange(rows, TODAY).single().title)
    }

    @Test
    fun categoryChangeNeedsBothThirtyPercentAndFiveHundred() {
        val small = listOf(txn(900, date = oct(5), categoryId = TestData.FOOD), txn(500, date = sep(5), categoryId = TestData.FOOD))
        assertTrue(engine.categoryChange(small, TODAY).isEmpty()) // +80% but only ₹400
        val shallow = listOf(txn(12000, date = oct(5), categoryId = TestData.FOOD), txn(10000, date = sep(5), categoryId = TestData.FOOD))
        assertTrue(engine.categoryChange(shallow, TODAY).isEmpty()) // ₹2,000 but only 20%
        val down = listOf(txn(1200, date = oct(5), categoryId = TestData.FOOD), txn(2000, date = sep(5), categoryId = TestData.FOOD))
        assertEquals("Food ↓ 40% — ₹1,200 vs ₹2,000", engine.categoryChange(down, TODAY).single().title)
    }

    @Test
    fun frequencyFromEightPayments() {
        val rows = (1..14).map { txn(if (it == 1) 288 else 276, date = oct(it), payee = "Swiggy") }
        assertEquals("Swiggy 14 times this month (₹3,876)", engine.frequency(rows, TODAY).single().title)
        assertTrue(engine.frequency(rows.take(7), TODAY).isEmpty())
    }

    @Test
    fun smallLeaksFromThousand() {
        val rows = (1..20).map { txn(50, date = oct(it % 24 + 1), payee = "Tea $it") }
        assertEquals("Small leaks: ₹1,000", engine.smallLeaks(rows, TODAY)!!.title)
        assertNull(engine.smallLeaks(rows.drop(1), TODAY))
        assertNull(engine.smallLeaks(rows.map { it.copy(amountPaise = 10_000) }, TODAY))
    }

    @Test
    fun weekendVsWeekday() {
        // Last 4 weeks: 8 weekend days, 20 weekdays.
        val days = (0L..27L).map { TODAY.minusDays(it) }
        val rows = days.map { d -> txn(if (d.dayOfWeek.value >= 6) 500 else 300, date = d) }
        val i = engine.weekendVsWeekday(rows, TODAY)!!
        assertEquals("Weekends cost more", i.title)
        assertEquals("₹500/day on weekends vs ₹300/day on weekdays (last 4 weeks)", i.text)
        assertNull(engine.weekendVsWeekday(days.map { txn(300, date = it) }, TODAY))
    }

    @Test
    fun timeOfDayPicksMostExpensiveBand() {
        val rows = listOf(txn(100, date = oct(2), hour = 9), txn(700, date = oct(3), hour = 23), txn(200, date = oct(4), hour = 19))
        val i = engine.timeOfDay(rows, TODAY)!!
        assertEquals("Late nights cost you the most", i.title)
        assertTrue(i.text.startsWith("₹700 (70%)"))
    }

    @Test
    fun recurringMonthlyAndWeekly() {
        val netflix = listOf(oct(5), sep(5), LocalDate.of(2026, 8, 6)).map { txn(649, date = it, payee = "Netflix") }
        val gym = listOf(oct(21), oct(14), oct(7)).map { txn(300, date = it, payee = "Gym") }
        val noise = listOf(oct(1), oct(2), oct(3)).map { txn(80, date = it, payee = "Tea") }
        val out = engine.recurring(netflix + gym + noise, TODAY)
        val n = out.first { it.payee == "Netflix" }
        assertEquals(3, n.count)
        assertEquals(64_900L, n.monthlyPaise)
        assertEquals(oct(5).plusDays(n.periodDays.toLong()), n.nextExpected)
        val g = out.first { it.payee == "Gym" }
        assertEquals(7, g.periodDays)
        assertEquals(oct(28), g.nextExpected)
        assertEquals(130_000L, g.monthlyPaise) // ₹300 x 52 / 12
        assertTrue(out.none { it.payee == "Tea" })
    }

    @Test
    fun recurringRequiresSimilarAmount() {
        val rows = listOf(txn(649, date = oct(5), payee = "X"), txn(900, date = sep(5), payee = "X"))
        assertTrue(engine.recurring(rows, TODAY).isEmpty())
    }

    @Test
    fun forecastMath() {
        // ₹24,000 by the 24th of a 31-day month -> ₹31,000; ₹30,000 budget is crossed on day 30.
        val rows = (1..24).map { txn(1000, date = oct(it)) }
        val f = engine.forecast(rows, TODAY, 3_000_000)
        assertEquals(2_400_000L, f.monthToDate)
        assertEquals(3_100_000L, f.projected)
        assertEquals(30, f.exceedDay)
        assertEquals(
            "On pace for ₹31,000 vs ₹30,000 budget — exceeds around the 30th.",
            engine.forecastInsight(f).title,
        )
        val under = engine.forecast(rows, TODAY, 4_000_000)
        assertNull(under.exceedDay)
        assertTrue(engine.forecast(rows, TODAY, 2_000_000).alreadyOver)
    }

    @Test
    fun forecastExampleFromSpec() {
        // ₹29,419 by the 24th (rounded) projects to ~₹38,000 against ₹30,000, crossing on the 25th.
        val f = engine.forecast(listOf(txn(29_419, date = oct(10))), TODAY, 3_000_000)
        assertEquals(3_799_954L, f.projected)
        assertEquals(25, f.exceedDay)
    }

    @Test
    fun suggestionCapsAtThreeMonthMedian() {
        val history = listOf(
            txn(3000, date = sep(10), categoryId = TestData.FOOD),
            txn(2800, date = LocalDate.of(2026, 8, 10), categoryId = TestData.FOOD),
            txn(3500, date = LocalDate.of(2026, 7, 10), categoryId = TestData.FOOD),
        )
        // ₹3,252 by the 24th -> ₹4,200 projected; median ₹3,000 -> save ₹1,200.
        val cur = txn(3252, date = oct(3), categoryId = TestData.FOOD)
        val s = engine.suggestion(history + cur, TODAY)!!
        assertEquals("Cap Food at ₹3,000 → save ~₹1,200/mo", s.title)
        assertEquals(InsightAction.SetBudget(TestData.FOOD, 300_000), s.action)
    }

    @Test
    fun streakCountsCompletedDaysUnderLimit() {
        val rows = listOf(
            txn(1500, date = TODAY.minusDays(6)),
            txn(500, date = TODAY.minusDays(5)),
            txn(900, date = TODAY.minusDays(3)),
            txn(5000, date = TODAY), // today does not break the streak
        )
        assertEquals(5, engine.streak(rows, TODAY, 100_000))
        assertTrue(engine.all(rows, TODAY, 100_000, 3_000_000).any { it.title == "5 days under your daily limit 🎉" })
    }

    @Test
    fun ordinals() {
        assertEquals(listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "24th"),
            listOf(1, 2, 3, 4, 11, 12, 13, 21, 22, 24).map(InsightsEngine::ordinal))
    }
}
