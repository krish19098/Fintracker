package com.krish.spendsense.domain

import com.krish.spendsense.domain.TestData.TODAY
import com.krish.spendsense.domain.TestData.txn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class AlertEngineTest {
    @Test
    fun dailyLimitWarnsThenAlertsOncePerDay() {
        val limit = 100_000L
        assertNull(AlertEngine.dailyLimit(79_900, limit, 80, emptySet(), TODAY))
        val warn = AlertEngine.dailyLimit(80_000, limit, 80, emptySet(), TODAY)!!
        assertEquals(80, warn.level)
        assertEquals(listOf("daily:2026-10-24:80"), warn.keys)
        assertNull(AlertEngine.dailyLimit(90_000, limit, 80, warn.keys.toSet(), TODAY))
        val full = AlertEngine.dailyLimit(100_000, limit, 80, warn.keys.toSet(), TODAY)!!
        assertEquals(100, full.level)
        assertNull(AlertEngine.dailyLimit(150_000, limit, 80, (warn.keys + full.keys).toSet(), TODAY))
        // Next day starts fresh.
        assertEquals(100, AlertEngine.dailyLimit(150_000, limit, 80, full.keys.toSet(), TODAY.plusDays(1))!!.level)
    }

    @Test
    fun jumpingPastBothLevelsFiresOnlyTheHigher() {
        val a = AlertEngine.dailyLimit(120_000, 100_000, 80, emptySet(), TODAY)!!
        assertEquals(100, a.level)
        assertEquals(2, a.keys.size)
    }

    @Test
    fun categoryBudgetAlerts() {
        val food = TestData.categories.first { it.id == TestData.FOOD }.copy(monthlyBudgetPaise = 300_000)
        val m = YearMonth.of(2026, 10)
        assertEquals(80, AlertEngine.categoryBudget(food, 250_000, 80, emptySet(), m)!!.level)
        assertEquals("Food budget exceeded", AlertEngine.categoryBudget(food, 300_000, 80, emptySet(), m)!!.title)
        assertNull(AlertEngine.categoryBudget(food.copy(monthlyBudgetPaise = null), 900_000, 80, emptySet(), m))
    }

    @Test
    fun largeTransaction() {
        assertTrue(AlertEngine.isLarge(txn(5000), 500_000))
        assertFalse(AlertEngine.isLarge(txn(4999), 500_000))
    }

    @Test
    fun texts() {
        assertEquals("₹250 → Ravi Tea Stall · Today ₹1,840 / ₹2,000", AlertEngine.txnText(txn(250, payee = "Ravi Tea Stall"), 184_000, 200_000))
        val cats = TestData.categories.associateBy { it.id }
        val day = listOf(
            txn(620, payee = "Swiggy", categoryId = TestData.FOOD),
            txn(784, payee = "Zomato", categoryId = TestData.FOOD),
            txn(936, payee = "Amazon", categoryId = TestData.SHOPPING),
        )
        assertEquals("₹2,340 today · 60% Food · biggest: Amazon ₹936", AlertEngine.daySummary(day, cats))
        assertNull(AlertEngine.daySummary(emptyList(), cats))
        assertEquals(
            "₹2,340 spent last week · ↑ 17% vs the week before · top: Food ₹1,404",
            AlertEngine.digest("last week", "the week before", day, listOf(txn(2000)), cats),
        )
    }
}
