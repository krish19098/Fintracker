package com.krish.spendsense.domain

import com.krish.spendsense.domain.TestData.FOOD
import com.krish.spendsense.domain.TestData.SHOPPING
import com.krish.spendsense.domain.TestData.catId
import com.krish.spendsense.domain.TestData.categories
import com.krish.spendsense.domain.TestData.txn
import com.krish.spendsense.parse.Direction
import com.krish.spendsense.parse.IncomeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingDataStatsTest {
    private val uncategorised = catId(CatKeys.UNCATEGORISED)
    private val groceries = catId(CatKeys.GROCERIES)
    private val salary = catId(CatKeys.income(IncomeKind.SALARY))

    private fun labelled(rupees: Long, category: Long, payee: String?, vpa: String? = null, direction: Direction = Direction.DEBIT) =
        txn(rupees, payee = payee, vpa = vpa, categoryId = category, direction = direction).copy(categoryUserSet = true)

    @Test
    fun emptyDatabaseIsNotReady() {
        val s = TrainingDataStats.of(emptyList(), categories)
        assertEquals(0, s.totalTxns)
        assertEquals(0, s.labels)
        assertEquals(0, s.reviewRatePct)
        assertTrue(s.perCategory.isEmpty())
        assertFalse(s.ready)
    }

    @Test
    fun countsAggregatesAndGroupsByCategory() {
        val txns = listOf(
            labelled(100, FOOD, "Ravi Tea Stall"),
            labelled(120, FOOD, "RAVI  tea-stall"), // same normalised payee key
            labelled(300, FOOD, "Bakery Corner"),
            labelled(900, SHOPPING, null, vpa = "shop@ybl"),
            labelled(50_000, salary, "ACME", direction = Direction.CREDIT), // income: not in the v1 dataset
            txn(200, payee = "Swiggy", categoryId = FOOD), // rule-assigned: not a label
            txn(80, payee = "Mystery", categoryId = uncategorised).copy(needsReview = true),
            txn(60, payee = "Other", categoryId = null).copy(needsReview = true),
            txn(40, payee = null, categoryId = uncategorised).copy(categoryUserSet = true), // Uncategorised is not a class
        )
        val s = TrainingDataStats.of(txns, categories)
        assertEquals(9, s.totalTxns)
        assertEquals(8, s.spendTxns)
        assertEquals(4, s.labels)
        assertEquals(3, s.labelPayees)
        assertEquals(listOf(CategoryLabels("Food", 3, 2), CategoryLabels("Shopping", 1, 1)), s.perCategory)
        assertEquals(3, s.uncategorised)
        assertEquals(2, s.needsReview)
        assertEquals(22, s.reviewRatePct) // 2 of 9
    }

    @Test
    fun readinessNeedsThirtyLabelsAndThreeCategoriesWithFive() {
        fun rows(category: Long, n: Int) = (1..n).map { labelled(100, category, "Payee $category-$it") }
        val twoBigCategories = rows(FOOD, 20) + rows(SHOPPING, 10)
        assertFalse(TrainingDataStats.of(twoBigCategories, categories).ready) // 30 labels, only 2 categories

        val threeButFewLabels = rows(FOOD, 5) + rows(SHOPPING, 5) + rows(groceries, 5)
        assertEquals(3, TrainingDataStats.of(threeButFewLabels, categories).categoriesWithEnoughLabels)
        assertFalse(TrainingDataStats.of(threeButFewLabels, categories).ready) // 15 labels

        val ready = rows(FOOD, 16) + rows(SHOPPING, 9) + rows(groceries, 5)
        val s = TrainingDataStats.of(ready, categories)
        assertEquals(30, s.labels)
        assertTrue(s.ready)
        assertFalse(TrainingDataStats.of(ready.drop(1), categories).ready) // 29 labels
    }

    @Test
    fun aggregatesCarryNoPayeeText() {
        val s = TrainingDataStats.of(listOf(labelled(100, FOOD, "Ravi Tea Stall", vpa = "ravi.tea@okaxis")), categories)
        val rendered = s.toString().lowercase()
        assertFalse(rendered, "ravi" in rendered)
        assertFalse(rendered, "okaxis" in rendered)
    }
}
