package com.krish.spendsense.domain

import com.krish.spendsense.data.Account
import com.krish.spendsense.data.ExcludedReason
import com.krish.spendsense.data.Txn
import com.krish.spendsense.parse.Direction
import kotlin.math.abs

/** Rules for transactions that never count as spending. */
object Exclusions {
    const val SELF_TRANSFER_WINDOW_MS = 10 * 60_000L
    const val REFUND_WINDOW_MS = 30L * 24 * 60 * 60_000L

    private val CC_WORDS = Regex("""credit\s*card|\bcc\s*payment|card\s*bill""", RegexOption.IGNORE_CASE)
    val REFUND_WORDS = Regex("""refund|reversal|reversed""", RegexOption.IGNORE_CASE)

    private fun text(t: Txn) = listOfNotNull(t.payeeName, t.payeeVpa, t.note).joinToString(" ")

    /** Credit-card bill: wording, a user-marked CC payee, or a transfer to one of the user's credit cards. */
    fun isCcBill(t: Txn, ccPayeeKeys: Set<String>, ownAccounts: List<Account>): Boolean {
        if (t.direction != Direction.DEBIT) return false
        if (CC_WORDS.containsMatchIn(text(t))) return true
        if (Categorizer.payeeKey(t.payeeName, t.payeeVpa)?.let { it in ccPayeeKeys } == true) return true
        return ownAccountFor(t, ownAccounts)?.isCreditCard == true
    }

    fun isRefund(t: Txn): Boolean = t.direction == Direction.CREDIT && REFUND_WORDS.containsMatchIn(text(t))

    /**
     * Original debit for a refund within 30 days before it: same payee preferred, then the most
     * recent debit of at least the refunded amount.
     */
    fun findRefundOriginal(refund: Txn, candidates: List<Txn>): Txn? {
        val eligible = candidates.filter {
            it.id != refund.id && it.direction == Direction.DEBIT && it.excludedReason == null &&
                it.amountPaise >= refund.amountPaise &&
                refund.timestamp - it.timestamp in 0..REFUND_WINDOW_MS
        }
        val key = Categorizer.payeeKey(refund.payeeName, refund.payeeVpa)
        return eligible.filter { key != null && Categorizer.payeeKey(it.payeeName, it.payeeVpa) == key }
            .maxByOrNull { it.timestamp }
            ?: eligible.filter { it.amountPaise == refund.amountPaise }.maxByOrNull { it.timestamp }
    }

    /** Own account a debit's numeric counterparty tail points to (never the source account). */
    fun ownAccountFor(t: Txn, ownAccounts: List<Account>): Account? {
        val tail = t.counterpartyTail?.takeIf { s -> s.all { it.isDigit() } } ?: return null
        return ownAccounts.singleOrNull { it.isOwn && it.id != t.accountId && it.last4.endsWith(tail) }
    }

    /**
     * Opposite leg of a self-transfer: same amount, opposite direction, within ±10 minutes, on a
     * different own account, not already excluded.
     */
    fun findSelfTransferPair(t: Txn, candidates: List<Txn>, ownAccountIds: Set<Long>): Txn? {
        if (t.accountId == null || t.accountId !in ownAccountIds) return null
        return candidates.filter {
            it.id != t.id && it.direction != t.direction && it.amountPaise == t.amountPaise &&
                it.accountId != null && it.accountId != t.accountId && it.accountId in ownAccountIds &&
                it.excludedReason == null && abs(it.timestamp - t.timestamp) <= SELF_TRANSFER_WINDOW_MS
        }.minByOrNull { abs(it.timestamp - t.timestamp) }
    }

    /** Self-transfer evidence available from the transaction alone. */
    fun isSelfTransferAlone(t: Txn, ownAccounts: List<Account>, ownVpas: Set<String>): Boolean =
        (t.payeeVpa != null && t.payeeVpa.lowercase() in ownVpas) ||
            (t.direction == Direction.DEBIT && ownAccountFor(t, ownAccounts)?.isCreditCard == false)

    /** Automatic exclusion for a new transaction, before pairing with other rows. */
    fun autoReason(t: Txn, ownAccounts: List<Account>, ownVpas: Set<String>, ccPayeeKeys: Set<String>): ExcludedReason? =
        when {
            t.exclusionUserSet -> t.excludedReason
            isCcBill(t, ccPayeeKeys, ownAccounts) -> ExcludedReason.CC_BILL
            isSelfTransferAlone(t, ownAccounts, ownVpas) -> ExcludedReason.SELF_TRANSFER
            else -> null
        }
}

/** Spending / income totals, shared by Home, alerts and insights. */
object Totals {
    /** A linked refund nets off against spending; every other excluded row is ignored. */
    fun isNettingRefund(t: Txn) = t.excludedReason == ExcludedReason.REFUND && t.linkedTxnId != null

    fun counts(t: Txn, excludedAccounts: Set<Long>) = t.accountId == null || t.accountId !in excludedAccounts

    /** Signed contribution to spending: +debit, -linked refund, 0 otherwise. */
    fun spendDelta(t: Txn, excludedAccounts: Set<Long> = emptySet()): Long = when {
        !counts(t, excludedAccounts) -> 0
        t.direction == Direction.DEBIT && t.excludedReason == null -> t.amountPaise
        isNettingRefund(t) -> -t.amountPaise
        else -> 0
    }

    fun spend(txns: List<Txn>, excludedAccounts: Set<Long> = emptySet()): Long =
        txns.sumOf { spendDelta(it, excludedAccounts) }.coerceAtLeast(0)

    fun income(txns: List<Txn>, excludedAccounts: Set<Long> = emptySet()): Long =
        txns.filter { it.direction == Direction.CREDIT && it.excludedReason == null && counts(it, excludedAccounts) }
            .sumOf { it.amountPaise }

    /**
     * Spend per category; a linked refund is charged back to the original's category when known.
     */
    fun byCategory(txns: List<Txn>, excludedAccounts: Set<Long> = emptySet()): Map<Long?, Long> {
        val byId = txns.associateBy { it.id }
        val out = HashMap<Long?, Long>()
        for (t in txns) {
            val d = spendDelta(t, excludedAccounts)
            if (d == 0L) continue
            val cat = if (d < 0) byId[t.linkedTxnId]?.categoryId ?: t.categoryId else t.categoryId
            out[cat] = (out[cat] ?: 0) + d
        }
        return out.filterValues { it > 0 }
    }
}
