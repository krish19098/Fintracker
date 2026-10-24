package com.krish.spendsense.data

import com.krish.spendsense.domain.TestData.txn
import com.krish.spendsense.parse.Source
import com.krish.spendsense.parse.TxnApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TxnMergeTest {
    private val sms = txn(19, payee = "Google India Digita", accountId = 7, ref = "512920989783", source = Source.SMS)
        .copy(balanceAfterPaise = 1234, simSlot = 2, app = TxnApp.OTHER_UPI)
    private val notif = txn(19, minute = 1, payee = "Google India Digital Services", accountId = null, ref = "512920989783", source = Source.NOTIF)
        .copy(app = TxnApp.GPAY)

    private fun assertMerged(m: Txn) {
        assertEquals(7L, m.accountId)
        assertEquals(1234L, m.balanceAfterPaise)
        assertEquals(2, m.simSlot)
        assertEquals("Google India Digital Services", m.payeeName)
        assertEquals(TxnApp.GPAY, m.app)
        assertEquals(Source.SMS.flag or Source.NOTIF.flag, m.sourceFlags)
        assertEquals(sms.timestamp, m.timestamp)
    }

    @Test
    fun smsThenNotification() {
        val m = TxnMerge.merge(sms, notif)
        assertMerged(m)
        assertEquals(sms.id, m.id)
    }

    @Test
    fun notificationThenSms() {
        val m = TxnMerge.merge(notif, sms)
        assertMerged(m)
        assertEquals(notif.id, m.id)
    }

    @Test
    fun noRefFallbackWithinThreeMinutes() {
        val n = notif.copy(upiRef = null, timestamp = sms.timestamp + 2 * 60_000)
        assertEquals(sms, TxnMerge.findFallback(n, listOf(sms)))
        assertNull(TxnMerge.findFallback(n.copy(timestamp = sms.timestamp + 4 * 60_000), listOf(sms)))
        assertNull(TxnMerge.findFallback(n.copy(amountPaise = 1901), listOf(sms)))
        assertNull(TxnMerge.findFallback(n.copy(accountId = 8), listOf(sms)))
        // Two SMS never merge by fallback (two genuine identical payments).
        assertNull(TxnMerge.findFallback(sms.copy(id = 99, upiRef = null), listOf(sms)))
        // Both sides with different refs are different transactions.
        assertNull(TxnMerge.findFallback(n.copy(upiRef = "600792645725"), listOf(sms)))
    }

    @Test
    fun rawHashIsStableAndDistinct() {
        val a = TxnMerge.rawHash("JK-CUBANK-S", "body", 1)
        assertEquals(a, TxnMerge.rawHash("JK-CUBANK-S", "body", 1))
        assertNotEquals(a, TxnMerge.rawHash("JK-CUBANK-S", "body", 2))
        assertEquals(64, a.length)
    }

    @Test
    fun alphabeticCounterpartyBecomesMerchantName() {
        val p = com.krish.spendsense.parse.SmsParser.parse(
            "JK-CUBANK-S",
            "Your a/c no. XXXXXXXX6578 is debited for Rs.407.00 on 07-08-2026 and credited to a/c no. XXXXXXXXIPAY (UPI Ref no 212621537807)",
            0L,
        ) as com.krish.spendsense.parse.SmsOutcome.Parsed
        assertEquals("IPAY", TxnMerge.toTxn(p.txn, "h", null, "CUBANK").payeeName)
        val numeric = p.txn.copy(counterpartyTail = "2708")
        assertNull(TxnMerge.toTxn(numeric, "h", null, "CUBANK").payeeName)
    }
}
