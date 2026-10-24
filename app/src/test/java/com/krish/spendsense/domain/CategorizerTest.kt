package com.krish.spendsense.domain

import com.krish.spendsense.data.KeywordRule
import com.krish.spendsense.domain.TestData.catId
import com.krish.spendsense.domain.TestData.txn
import com.krish.spendsense.parse.Direction
import com.krish.spendsense.parse.IncomeKind
import com.krish.spendsense.parse.UpiType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategorizerTest {
    private val keywords = DefaultCategories.keywords.flatMap { (key, words) ->
        words.map { KeywordRule(keyword = it, categoryId = catId(key)) }
    }

    private fun categorizer(rules: Map<String, Long> = emptyMap()) = Categorizer(rules, keywords, TestData.categories)

    @Test
    fun payeeRuleBeatsKeyword() {
        val c = categorizer(mapOf("swiggy@icici" to TestData.SHOPPING))
        val d = c.categorize(txn(250, payee = "Swiggy", vpa = "swiggy@icici"))
        assertEquals(TestData.SHOPPING, d.categoryId)
        assertFalse(d.needsReview)
    }

    @Test
    fun payeeRuleKeyedByNameWhenNoVpa() {
        val c = categorizer(mapOf("ravi tea stall" to TestData.SHOPPING))
        assertEquals(TestData.SHOPPING, c.categorize(txn(10, payee = "  RAVI  Tea-Stall ")).categoryId)
    }

    @Test
    fun keywordBeatsPersonVpa() {
        assertEquals(TestData.FOOD, categorizer().categorize(txn(250, payee = "Ravi Tea Stall", vpa = "ravi@okaxis")).categoryId)
    }

    @Test
    fun keywordsMatchAtWordStartOnly() {
        assertEquals(catId(CatKeys.UNCATEGORISED), categorizer().categorize(txn(50, payee = "Steam Games")).categoryId)
        assertEquals(catId(CatKeys.TRANSPORT), categorizer().categorize(txn(50, payee = "OLACABS")).categoryId)
        assertEquals(catId(CatKeys.RENT), categorizer().categorize(txn(15000, payee = "Owner", note = "Rent Oct")).categoryId)
    }

    @Test
    fun personVpaDefaultsToTransfers() {
        assertEquals(catId(CatKeys.P2P), categorizer().categorize(txn(500, payee = null, vpa = "9876543210@ybl")).categoryId)
        assertEquals(catId(CatKeys.UNCATEGORISED), categorizer().categorize(txn(500, payee = null, vpa = "q123456789@ybl")).categoryId)
        assertEquals(catId(CatKeys.P2P), categorizer().categorize(txn(500, payee = "X", upiType = UpiType.P2A)).categoryId)
    }

    @Test
    fun unknownIsUncategorisedNeedingReview() {
        val d = categorizer().categorize(txn(407, payee = "IPAY"))
        assertEquals(catId(CatKeys.UNCATEGORISED), d.categoryId)
        assertTrue(d.needsReview)
    }

    @Test
    fun creditsGetIncomeCategories() {
        val c = categorizer()
        assertEquals(
            catId(CatKeys.income(IncomeKind.DIVIDENDS_INTEREST)),
            c.categorize(txn(161, direction = Direction.CREDIT, payee = "NMDC LTD"), IncomeKind.DIVIDENDS_INTEREST).categoryId,
        )
        assertEquals(catId(CatKeys.income(IncomeKind.REFUND)), c.categorize(txn(99, direction = Direction.CREDIT, note = "Refund for order")).categoryId)
        assertEquals(catId(CatKeys.income(IncomeKind.TRANSFER_IN)), c.categorize(txn(500, direction = Direction.CREDIT, payee = null, vpa = "anita@oksbi")).categoryId)
        assertEquals(catId(CatKeys.income(IncomeKind.OTHER_INCOME)), c.categorize(txn(500, direction = Direction.CREDIT, payee = "ACME")).categoryId)
    }

    @Test
    fun spendRuleNotAppliedToCredit() {
        val c = categorizer(mapOf("ravi" to TestData.FOOD))
        assertEquals(catId(CatKeys.income(IncomeKind.OTHER_INCOME)), c.categorize(txn(100, direction = Direction.CREDIT, payee = "Ravi")).categoryId)
    }
}
