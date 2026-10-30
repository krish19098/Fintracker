package com.krish.spendsense.domain

import com.krish.spendsense.data.Category
import com.krish.spendsense.data.CategoryKind
import com.krish.spendsense.data.Txn
import com.krish.spendsense.parse.Direction

data class CategoryLabels(val name: String, val labels: Int, val distinctPayees: Int)

/**
 * ML milestone M0: how much user-confirmed data exists for a future spending classifier.
 * Aggregate counts only; payee names and VPAs are used for distinct-counting and never leave this object.
 *
 * A label is a debit whose category the user chose themselves (`categoryUserSet`) and that is a real
 * spending category. Rows recategorised by "Always categorise" propagation keep `categoryUserSet = false`,
 * so they copy one decision and are not counted again. Income is not part of the v1 dataset.
 */
data class TrainingDataStats(
    val totalTxns: Int,
    val spendTxns: Int,
    val labels: Int,
    val labelPayees: Int,
    val perCategory: List<CategoryLabels>,
    val uncategorised: Int,
    val needsReview: Int,
) {
    /** needsReview as a whole percentage of all transactions. */
    val reviewRatePct: Int get() = if (totalTxns == 0) 0 else ((needsReview * 100L + totalTxns / 2) / totalTxns).toInt()
    val categoriesWithEnoughLabels: Int get() = perCategory.count { it.labels >= MIN_LABELS_PER_CATEGORY }

    /** Provisional engineering thresholds for starting experiments; not a measure of model accuracy. */
    val ready: Boolean get() = labels >= MIN_LABELS && categoriesWithEnoughLabels >= MIN_CATEGORIES

    companion object {
        const val MIN_LABELS = 30
        const val MIN_LABELS_PER_CATEGORY = 5
        const val MIN_CATEGORIES = 3

        fun of(txns: List<Txn>, categories: List<Category>): TrainingDataStats {
            val byId = categories.associateBy { it.id }
            val uncategorisedId = categories.firstOrNull { it.systemKey == CatKeys.UNCATEGORISED }?.id
            val spend = txns.filter { it.direction == Direction.DEBIT }
            val labelled = spend.filter { t ->
                val c = t.categoryId?.let { byId[it] }
                t.categoryUserSet && c != null && c.kind == CategoryKind.SPEND && c.id != uncategorisedId
            }
            fun payees(rows: List<Txn>) = rows.mapNotNullTo(HashSet()) { Categorizer.payeeKey(it.payeeName, it.payeeVpa) }.size
            val perCategory = labelled.groupBy { it.categoryId!! }
                .map { (id, rows) -> CategoryLabels(byId.getValue(id).name, rows.size, payees(rows)) }
                .sortedWith(compareByDescending<CategoryLabels> { it.labels }.thenBy { it.name })
            return TrainingDataStats(
                totalTxns = txns.size,
                spendTxns = spend.size,
                labels = labelled.size,
                labelPayees = payees(labelled),
                perCategory = perCategory,
                uncategorised = txns.count { it.categoryId == null || it.categoryId == uncategorisedId },
                needsReview = txns.count { it.needsReview },
            )
        }
    }
}
