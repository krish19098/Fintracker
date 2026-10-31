package com.krish.spendsense.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** Fallback reader for bank SMS that no built-in template matches, from any bank (here a user-added "SBI"). */
class GeneralSmsReaderTest {
    private val keys = MessageFilter.DEFAULT_SENDER_KEYS + ("SBIINB" to "SBI")
    private val receivedAt = ZonedDateTime.of(LocalDate.of(2026, 10, 30), LocalTime.of(18, 5), ParseUtils.IST).toInstant().toEpochMilli()

    private fun parse(body: String, sender: String = "AD-SBIINB-S") = SmsParser.parse(sender, body, receivedAt, senderKeys = keys)
    private fun read(body: String) = (parse(body) as SmsOutcome.Parsed).txn

    private fun at(date: LocalDate) = ParseUtils.resolveTimestamp(date, null, receivedAt, null)

    @Test
    fun neftDebitFromAnyBank() {
        val t = read("Your A/c XXXXX1234 has been debited by Rs.5,000.00 on 30/10/26 towards NEFT to RAVI KUMAR. Avl Bal Rs.12,345.67")
        assertEquals(
            ParsedTxn(
                amountPaise = 500_000, direction = Direction.DEBIT, bank = "SBI", timestamp = at(LocalDate.of(2026, 10, 30)),
                source = Source.SMS, app = TxnApp.NON_UPI, accountTail = "1234", note = "NEFT transfer",
                balanceAfterPaise = 1_234_567, needsReview = true,
            ),
            t,
        )
    }

    @Test
    fun impsCreditWithRefAndBalance() {
        val t = read("Dear Customer, Acct XX5678 is credited with INR 2,500.00 on 29-Oct-26 from IMPS Ref 630212345678. Available balance INR 7,500.00")
        assertEquals(Direction.CREDIT, t.direction)
        assertEquals(250_000L, t.amountPaise)
        assertEquals("5678", t.accountTail)
        assertEquals("630212345678", t.upiRef)
        assertEquals(TxnApp.NON_UPI, t.app)
        assertEquals("IMPS transfer", t.note)
        assertEquals(750_000L, t.balanceAfterPaise)
        assertEquals(at(LocalDate.of(2026, 10, 29)), t.timestamp)
        assertTrue(t.needsReview)
    }

    @Test
    fun atmCardAndVpaVariants() {
        val atm = read("Rs.2000 withdrawn from A/c **9012 at ATM on 30-10-2026")
        assertEquals("ATM withdrawal", atm.note)
        assertEquals("9012", atm.accountTail)

        // The balance comes first; the transaction amount is the next one. A yyyy-MM-dd date is not misread.
        val card = read("Avl Bal Rs.900.00. Rs.100 spent on Card x4321 at SHOP on 2026-10-30")
        assertEquals(10_000L, card.amountPaise)
        assertEquals("4321", card.accountTail)
        assertEquals(receivedAt, card.timestamp)

        val upi = read("Rs.150 debited from A/c XX1234 to VPA shop@okicici UPI Ref 630398765432")
        assertEquals("shop@okicici", upi.payeeVpa)
        assertEquals(TxnApp.OTHER_UPI, upi.app)
        assertEquals("UPI transfer", upi.note)
    }

    @Test
    fun remindersRequestsAndFailuresAreLeftForReview() {
        listOf(
            "Rs.999 will be debited from A/c XX1234 on 05-11-26 for NACH",
            "Payment of Rs.500 debited from A/c XX1234 failed. Amount will be refunded",
            "Rs.5000 debit is due on card XX1234 by 05-11-26",
            "RAVI has requested Rs.200 from A/c XX1234. Paid only if you approve",
        ).forEach { assertEquals(it, SmsOutcome.Unparsed("SBI", "SBIINB"), parse(it)) }
    }

    @Test
    fun builtInFormatsStillWinAndAreNotFlagged() {
        val hdfc = "Sent Rs.200.00\nFrom HDFC Bank A/C *9924\nTo EVEREST SYSTEMS YOGANAND \nOn 07/01/26\nRef 600792645725\nNot You?"
        val t = (SmsParser.parse("VM-HDFCBK-S", hdfc, receivedAt) as SmsOutcome.Parsed).txn
        assertEquals("EVEREST SYSTEMS YOGANAND", t.payeeName)
        assertFalse(t.needsReview)
        assertNull(t.note)
    }
}
