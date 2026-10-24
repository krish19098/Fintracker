package com.krish.spendsense.domain

import com.krish.spendsense.data.Category
import com.krish.spendsense.data.CategoryKind
import com.krish.spendsense.data.KeywordRule
import com.krish.spendsense.data.Txn
import com.krish.spendsense.parse.Direction
import com.krish.spendsense.parse.IncomeKind
import com.krish.spendsense.parse.UpiType

/** System keys of categories the logic depends on. */
object CatKeys {
    const val FOOD = "FOOD"
    const val GROCERIES = "GROCERIES"
    const val TRANSPORT = "TRANSPORT"
    const val BILLS = "BILLS"
    const val SHOPPING = "SHOPPING"
    const val ENTERTAINMENT = "ENTERTAINMENT"
    const val HEALTH = "HEALTH"
    const val RENT = "RENT"
    const val P2P = "P2P"
    const val EDUCATION = "EDUCATION"
    const val OTHERS = "OTHERS"
    const val UNCATEGORISED = "UNCATEGORISED"

    fun income(kind: IncomeKind) = "INC_${kind.name}"
}

data class CategorySeed(val key: String, val name: String, val icon: String, val color: Long, val kind: CategoryKind = CategoryKind.SPEND)

object DefaultCategories {
    val seeds = listOf(
        CategorySeed(CatKeys.FOOD, "Food", "restaurant", 0xFFE76F51),
        CategorySeed(CatKeys.GROCERIES, "Groceries", "cart", 0xFF2A9D8F),
        CategorySeed(CatKeys.TRANSPORT, "Transport", "car", 0xFF457B9D),
        CategorySeed(CatKeys.BILLS, "Bills & Recharge", "bolt", 0xFFF4A261),
        CategorySeed(CatKeys.SHOPPING, "Shopping", "bag", 0xFFB5179E),
        CategorySeed(CatKeys.ENTERTAINMENT, "Entertainment", "movie", 0xFF7209B7),
        CategorySeed(CatKeys.HEALTH, "Health", "health", 0xFFD62828),
        CategorySeed(CatKeys.RENT, "Rent", "home", 0xFF6D597A),
        CategorySeed(CatKeys.P2P, "Transfers to People", "people", 0xFF0077B6),
        CategorySeed(CatKeys.EDUCATION, "Education", "school", 0xFF588157),
        CategorySeed(CatKeys.OTHERS, "Others", "more", 0xFF8D99AE),
        CategorySeed(CatKeys.UNCATEGORISED, "Uncategorised", "help", 0xFF9E9E9E),
    ) + IncomeKind.entries.map {
        CategorySeed(CatKeys.income(it), it.label, "income", 0xFF2E7D32, CategoryKind.INCOME)
    }

    val keywords: Map<String, List<String>> = mapOf(
        CatKeys.FOOD to listOf("swiggy", "zomato", "restaurant", "hotel", "bakery", "tea", "cafe"),
        CatKeys.GROCERIES to listOf("bigbasket", "blinkit", "zepto", "dmart", "supermarket", "stores"),
        CatKeys.TRANSPORT to listOf("uber", "ola", "rapido", "metro", "irctc", "redbus", "fuel", "petrol"),
        CatKeys.BILLS to listOf("airtel", "jio", "bescom", "tneb", "electricity", "broadband", "recharge"),
        CatKeys.SHOPPING to listOf("amazon", "flipkart", "myntra", "ajio", "meesho"),
        CatKeys.ENTERTAINMENT to listOf("netflix", "spotify", "hotstar", "prime", "bookmyshow"),
        CatKeys.HEALTH to listOf("pharmacy", "apollo", "medplus", "hospital", "clinic"),
        CatKeys.RENT to listOf("rent"),
    )
}

data class CategoryDecision(val categoryId: Long?, val needsReview: Boolean)

/**
 * First hit wins: learned PayeeRule -> KeywordRule -> person-like VPA (Transfers to People)
 * -> Uncategorised (needs review). Credits get income categories instead.
 */
class Categorizer(
    private val payeeRules: Map<String, Long>,
    keywordRules: List<KeywordRule>,
    categories: List<Category>,
) {
    private val byId = categories.associateBy { it.id }
    private val byKey = categories.filter { it.systemKey != null }.associateBy { it.systemKey!! }
    private val keywordMatchers = keywordRules.map { keywordRegex(it.keyword) to it.categoryId }

    fun idOf(key: String): Long? = byKey[key]?.id

    fun categorize(t: Txn, incomeHint: IncomeKind? = null): CategoryDecision {
        val wantKind = if (t.direction == Direction.DEBIT) CategoryKind.SPEND else CategoryKind.INCOME
        payeeKey(t.payeeName, t.payeeVpa)?.let { key ->
            payeeRules[key]?.takeIf { byId[it]?.kind == wantKind }?.let { return CategoryDecision(it, false) }
        }
        return if (t.direction == Direction.CREDIT) income(t, incomeHint) else spend(t)
    }

    private fun spend(t: Txn): CategoryDecision {
        val haystack = listOfNotNull(t.payeeName, t.payeeVpa, t.note).joinToString(" ")
        keywordMatchers.firstOrNull { (r, id) -> byId[id]?.kind == CategoryKind.SPEND && r.containsMatchIn(haystack) }
            ?.let { return CategoryDecision(it.second, false) }
        val toPerson = t.payeeVpa?.let(::isPersonVpa) == true || t.upiType == UpiType.P2P || t.upiType == UpiType.P2A
        if (toPerson) return CategoryDecision(idOf(CatKeys.P2P), false)
        return CategoryDecision(idOf(CatKeys.UNCATEGORISED), true)
    }

    private fun income(t: Txn, hint: IncomeKind?): CategoryDecision {
        val text = listOfNotNull(t.payeeName, t.payeeVpa, t.note).joinToString(" ")
        val kind = hint ?: when {
            Regex("""\bsal(ary)?\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> IncomeKind.SALARY
            Exclusions.REFUND_WORDS.containsMatchIn(text) -> IncomeKind.REFUND
            Regex("""NACH_CR|ACH_CR|dividend|int\.pd|interest""", RegexOption.IGNORE_CASE).containsMatchIn(text) ->
                IncomeKind.DIVIDENDS_INTEREST
            t.payeeVpa?.let(::isPersonVpa) == true || t.upiType == UpiType.P2P || t.upiType == UpiType.P2A ||
                t.counterpartyTail?.all { it.isDigit() } == true -> IncomeKind.TRANSFER_IN
            else -> IncomeKind.OTHER_INCOME
        }
        return CategoryDecision(idOf(CatKeys.income(kind)), false)
    }

    companion object {
        /** Keywords match at a word start, so "tea" hits "Ravi Tea Stall" but not "Steam". */
        fun keywordRegex(keyword: String) =
            Regex("(?<![a-z0-9])" + Regex.escape(keyword.trim().lowercase()), RegexOption.IGNORE_CASE)

        /** Normalised VPA, else normalised name. */
        fun payeeKey(name: String?, vpa: String?): String? =
            vpa?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                ?: name?.lowercase()?.replace(Regex("[^a-z0-9]+"), " ")?.trim()?.takeIf { it.isNotEmpty() }

        private val personalHandles = setOf(
            "okaxis", "oksbi", "okhdfcbank", "okicici", "ybl", "ibl", "axl", "paytm", "upi", "apl", "yapl",
            "ptyes", "ptaxis", "pthdfc", "ptsbi", "naviaxis", "superyes",
        )
        private val merchantLocal = Regex(
            """^(q\d|paytmqr|bharatpe|gpay-|mer|pos)|merchant|store|shop|mart|enterprises|traders|services|pvt|ltd""",
            RegexOption.IGNORE_CASE,
        )

        fun isPersonVpa(vpa: String): Boolean {
            val local = vpa.substringBefore('@')
            val handle = vpa.substringAfter('@', "").lowercase()
            return handle in personalHandles && local.isNotEmpty() && !merchantLocal.containsMatchIn(local)
        }
    }
}
