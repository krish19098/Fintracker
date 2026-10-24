package com.krish.spendsense.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Synthetic cases for the UNVERIFIED notification formats; replace with real fixtures when available. */
class NotifParserTest {
    @Test
    fun gpayPaid() {
        val t = GpayParser.parse("₹250.00 paid to Ravi Tea Stall", "UPI transaction ID: 626017540942", 1000L)!!
        assertEquals(25000L, t.amountPaise)
        assertEquals(Direction.DEBIT, t.direction)
        assertEquals("Ravi Tea Stall", t.payeeName)
        assertEquals("626017540942", t.upiRef)
        assertEquals(TxnApp.GPAY, t.app)
        assertEquals(Source.NOTIF, t.source)
    }

    @Test
    fun gpayReceived() {
        val t = GpayParser.parse("Anita Sharma paid you ₹1,500", null, 1000L)!!
        assertEquals(150000L, t.amountPaise)
        assertEquals(Direction.CREDIT, t.direction)
        assertEquals("Anita Sharma", t.payeeName)
    }

    @Test
    fun paytmPaidAndReceived() {
        val d = PaytmParser.parse("Paid Rs.99 to Zomato", "UPI Ref No: 621970896384", 1L)!!
        assertEquals(9900L, d.amountPaise)
        assertEquals("Zomato", d.payeeName)
        assertEquals("621970896384", d.upiRef)
        val c = PaytmParser.parse("Received ₹500 from ravi.k@okaxis", null, 1L)!!
        assertEquals(Direction.CREDIT, c.direction)
        assertEquals("ravi.k@okaxis", c.payeeVpa)
        assertNull(c.payeeName)
    }

    @Test
    fun requestsAndPromosIgnored() {
        assertNull(GpayParser.parse("Ravi requested ₹200", "Pay now", 1L))
        assertNull(PaytmParser.parse("Get ₹100 cashback", "on your next recharge", 1L))
        assertNull(AppNotifParser.forPackage("com.whatsapp"))
    }
}
