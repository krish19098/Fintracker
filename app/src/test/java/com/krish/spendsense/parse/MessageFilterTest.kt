package com.krish.spendsense.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageFilterTest {
    @Test
    fun senderKeyStripsDltPrefixAndSuffix() {
        assertEquals("HDFCBK", MessageFilter.senderKey("VM-HDFCBK"))
        assertEquals("HDFCBK", MessageFilter.senderKey("ad-hdfcbk-s"))
        assertEquals("CUBANK", MessageFilter.senderKey("JK-CUBANK-S"))
        assertEquals("AXISBK", MessageFilter.senderKey("AX-AXISBK-T"))
        assertEquals("AXISBK", MessageFilter.senderKey("AXISBK"))
    }

    @Test
    fun dropsOtpPromoAndBalanceOnly() {
        assertEquals(
            MessageFilter.Reject.OTP,
            MessageFilter.rejectReason("123456 is your OTP for txn of Rs.500 debited at AMAZON. Do not share."),
        )
        assertEquals(
            MessageFilter.Reject.NO_TRANSACTION,
            MessageFilter.rejectReason("Avl Bal in A/c XX1234 is Rs.5,000.00 as on 23-10-26"),
        )
        assertEquals(
            MessageFilter.Reject.PROMOTIONAL,
            MessageFilter.rejectReason("Congratulations! Pre-approved loan of Rs.5,00,000 credited instantly. Click here"),
        )
        assertNull(MessageFilter.rejectReason("Sent Rs.200.00\nFrom HDFC Bank A/C *9924\nTo X\nOn 07/01/26\nRef 600792645725"))
    }

    @Test
    fun unknownSenderIgnoredKnownUnparsedReported() {
        assertEquals(SmsOutcome.NotBankSender, SmsParser.parse("VM-AMAZON", "Rs.500 debited", 0L))
        val out = SmsParser.parse("JK-CUBANK-S", "Rs.500 debited from your card ending 1234 at SHOP", 0L)
        assertEquals(SmsOutcome.Unparsed("CUB", "CUBANK"), out)
    }

    @Test
    fun customSenderKeyUsesSharedTemplates() {
        val body = "Your a/c no. XXXXXXXX1111 is debited for Rs.50.00 on 07-08-2026 and credited to a/c no. XXXXXXXX2222 (UPI Ref no 621970896384)"
        val out = SmsParser.parse("BX-SBIUPI-S", body, 0L, senderKeys = mapOf("SBIUPI" to "SBI"))
        assertTrue(out is SmsOutcome.Parsed && out.txn.bank == "SBI" && out.txn.accountTail == "1111")
    }

    @Test
    fun partialParsePrefillsReview() {
        val p = SmsParser.partial("CUB", "Rs.1,250.50 debited from A/c XX4321 to shop@ybl ref 621970896384", 0L)!!
        assertEquals(125050L, p.amountPaise)
        assertEquals("4321", p.accountTail)
        assertEquals("shop@ybl", p.payeeVpa)
        assertEquals("621970896384", p.upiRef)
    }
}
