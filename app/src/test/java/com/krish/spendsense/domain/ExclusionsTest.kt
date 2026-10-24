package com.krish.spendsense.domain

import com.krish.spendsense.data.Account
import com.krish.spendsense.data.ExcludedReason
import com.krish.spendsense.domain.TestData.TODAY
import com.krish.spendsense.domain.TestData.txn
import com.krish.spendsense.parse.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExclusionsTest {
    private val hdfc = Account(id = 1, bank = "HDFC", last4 = "9924", nickname = "HDFC")
    private val cub = Account(id = 2, bank = "CUB", last4 = "6578", nickname = "CUB")
    private val card = Account(id = 3, bank = "AXIS", last4 = "4444", nickname = "Card", isCreditCard = true)
    private val own = setOf(1L, 2L, 3L)

    @Test
    fun selfTransferPairsOppositeLegWithinTenMinutes() {
        val debit = txn(5000, hour = 10, minute = 0, accountId = 1)
        val credit = txn(5000, hour = 10, minute = 8, direction = Direction.CREDIT, accountId = 2)
        val tooLate = txn(5000, hour = 10, minute = 15, direction = Direction.CREDIT, accountId = 2)
        val sameAcct = txn(5000, hour = 10, minute = 1, direction = Direction.CREDIT, accountId = 1)
        assertEquals(credit, Exclusions.findSelfTransferPair(debit, listOf(tooLate, sameAcct, credit), own))
        assertNull(Exclusions.findSelfTransferPair(debit, listOf(tooLate, sameAcct), own))
        assertNull(Exclusions.findSelfTransferPair(debit.copy(accountId = 9), listOf(credit), own))
    }

    @Test
    fun counterpartyTailMatchingOwnAccountIsSelfTransfer() {
        val t = txn(376, accountId = 2, counterpartyTail = "9924")
        assertTrue(Exclusions.isSelfTransferAlone(t, listOf(hdfc, cub), emptySet()))
        assertFalse(Exclusions.isSelfTransferAlone(t.copy(counterpartyTail = "2708"), listOf(hdfc, cub), emptySet()))
        assertFalse(Exclusions.isSelfTransferAlone(t.copy(counterpartyTail = "IPAY"), listOf(hdfc, cub), emptySet()))
        assertTrue(Exclusions.isSelfTransferAlone(txn(10, vpa = "me@okhdfcbank"), emptyList(), setOf("me@okhdfcbank")))
    }

    @Test
    fun creditCardBill() {
        assertTrue(Exclusions.isCcBill(txn(9000, payee = "HDFC Credit Card"), emptySet(), emptyList()))
        assertTrue(Exclusions.isCcBill(txn(9000, payee = "CRED", note = "cc payment"), emptySet(), emptyList()))
        assertTrue(Exclusions.isCcBill(txn(9000, payee = "Cred Club"), setOf("cred club"), emptyList()))
        assertTrue(Exclusions.isCcBill(txn(9000, counterpartyTail = "4444"), emptySet(), listOf(card)))
        assertFalse(Exclusions.isCcBill(txn(9000, payee = "Amazon"), emptySet(), emptyList()))
        assertEquals(
            ExcludedReason.CC_BILL,
            Exclusions.autoReason(txn(9000, counterpartyTail = "4444"), listOf(hdfc, card), emptySet(), emptySet()),
        )
    }

    @Test
    fun refundLinksToOriginalWithin30Days() {
        val orig = txn(1200, date = TODAY.minusDays(5), payee = "Flipkart")
        val other = txn(1200, date = TODAY.minusDays(2), payee = "Myntra")
        val old = txn(1200, date = TODAY.minusDays(40), payee = "Flipkart")
        val refund = txn(1200, direction = Direction.CREDIT, payee = "Flipkart", note = "Refund")
        assertTrue(Exclusions.isRefund(refund))
        assertEquals(orig, Exclusions.findRefundOriginal(refund, listOf(old, other, orig)))
        // Without a same-payee debit, the most recent exact-amount debit wins.
        assertEquals(other, Exclusions.findRefundOriginal(refund.copy(payeeName = "Unknown"), listOf(old, other, orig)))
        assertNull(Exclusions.findRefundOriginal(refund, listOf(old)))
    }

    @Test
    fun linkedRefundNetsSpendingAndCreditsNeverOffset() {
        val orig = txn(1000, categoryId = TestData.SHOPPING)
        val refund = txn(400, direction = Direction.CREDIT, excluded = ExcludedReason.REFUND, linked = orig.id)
        val salary = txn(50000, direction = Direction.CREDIT)
        val self = txn(3000, excluded = ExcludedReason.SELF_TRANSFER)
        val rows = listOf(orig, refund, salary, self)
        assertEquals(60_000L, Totals.spend(rows))
        assertEquals(5_000_000L, Totals.income(rows))
        assertEquals(mapOf<Long?, Long>(TestData.SHOPPING to 60_000L), Totals.byCategory(rows))
        assertEquals(0L, Totals.spend(rows, excludedAccounts = setOf(1L)))
    }
}
