package com.krish.spendsense.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

class ParseUtilsTest {
    private fun ist(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ParseUtils.IST).toInstant().toEpochMilli()

    @Test
    fun amountsAreExactPaise() {
        assertEquals(12345670L, ParseUtils.parseAmountPaise("1,23,456.7"))
        assertEquals(1900L, ParseUtils.parseAmountPaise("19.00"))
        assertEquals(50000L, ParseUtils.parseAmountPaise("500"))
        assertEquals(400673L, ParseUtils.parseAmountPaise("4006.73"))
        assertEquals(1L, ParseUtils.parseAmountPaise("0.01"))
        assertNull(ParseUtils.parseAmountPaise("1.234"))
        assertNull(ParseUtils.parseAmountPaise("abc"))
    }

    @Test
    fun currencyMarkersAllAccepted() {
        val r = Regex(ParseUtils.AMT)
        listOf("Rs.250", "Rs 250", "INR 250.00", "₹250", "Rs.2,50,000.50").forEach {
            assertEquals(it, true, r.containsMatchIn(it))
        }
    }

    @Test
    fun dateFormats() {
        val d = LocalDate.of(2026, 10, 23)
        listOf("23-10-26", "23-10-2026", "23/10/26", "23/10/2026", "23-Oct-26", "23-OCT-2026").forEach {
            assertEquals(it, d, ParseUtils.parseDate(it))
        }
        assertNull(ParseUtils.parseDate("32-10-2026"))
    }

    @Test
    fun rrnDateDecodesYearDigitAndDayOfYear() {
        val now = ist(2026, 10, 24, 9, 0)
        assertEquals(LocalDate.of(2026, 9, 17), ParseUtils.rrnDate("626017540942", now))
        assertEquals(LocalDate.of(2025, 5, 9), ParseUtils.rrnDate("512920989783", now))
        assertNull(ParseUtils.rrnDate("212621537807".replaceRange(1, 4, "999"), now))
    }

    @Test
    fun dateOnlyUsesReceivedTimeOnSameDayElseNoon() {
        val received = ist(2026, 9, 17, 18, 5)
        assertEquals(received, ParseUtils.resolveTimestamp(LocalDate.of(2026, 9, 17), null, received, null))
        assertEquals(ist(2026, 9, 16, 12, 0), ParseUtils.resolveTimestamp(LocalDate.of(2026, 9, 16), null, received, null))
        assertEquals(
            ist(2026, 9, 17, 7, 30),
            ParseUtils.resolveTimestamp(LocalDate.of(2026, 9, 17), LocalTime.of(7, 30), received, null),
        )
    }

    @Test
    fun missingDateFallsBackToRrnDate() {
        val received = ist(2026, 9, 19, 8, 0)
        assertEquals(ist(2026, 9, 17, 12, 0), ParseUtils.resolveTimestamp(null, null, received, "626017540942"))
        assertEquals(received, ParseUtils.resolveTimestamp(null, null, received, null))
    }

    @Test
    fun rrnMismatchNeverRejects() {
        // cub_3's stand-in ref starts with 2 and does not follow the pattern; the SMS date wins.
        val received = ist(2026, 8, 7, 15, 12)
        assertEquals(received, ParseUtils.resolveTimestamp(LocalDate.of(2026, 8, 7), null, received, "212621537807"))
    }

    @Test
    fun normalizeToleratesWhitespaceAndSlashSpacing() {
        assertEquals("NACH_CR/NMDC LTD/ACH_CR", ParseUtils.normalize("NACH_CR/ NMDC  LTD/\nACH_CR"))
    }
}
