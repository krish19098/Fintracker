package com.krish.spendsense.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class MoneyTest {
    @Test
    fun indianGrouping() {
        assertEquals("₹0", Money.format(0))
        assertEquals("₹999", Money.format(99_900))
        assertEquals("₹1,000", Money.format(100_000))
        assertEquals("₹1,23,456", Money.format(12_345_600))
        assertEquals("₹12,34,567.89", Money.format(123_456_789))
        assertEquals("₹1,00,00,000", Money.format(1_000_000_000))
        assertEquals("₹19.50", Money.format(1950))
        assertEquals("₹19.00", Money.format(1900, forcePaise = true))
        assertEquals("-₹250", Money.format(-25_000))
    }

    @Test
    fun shortRoundsToRupee() {
        assertEquals("₹20", Money.short(1950))
        assertEquals("₹19", Money.short(1949))
    }

    @Test
    fun shortRoundsNegativesAwayFromZero() {
        assertEquals("-₹20", Money.short(-1950))
        assertEquals("-₹19", Money.short(-1949))
        assertEquals("₹0", Money.short(0))
    }

    @Test
    fun hugeInputIsRejectedNotOverflowed() {
        assertEquals(null, Money.parse("99999999999999999999"))
        assertEquals(null, Money.parse("1234567890123456"))
        assertEquals(99_999_999_999_999_900L, Money.parse("999999999999999"))
    }

    @Test
    fun customRangeIsOrderedAndCapped() {
        val today = LocalDate.of(2026, 10, 24)
        assertEquals(LocalDate.of(2026, 10, 1) to LocalDate.of(2026, 10, 10), Dates.clampRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10), today))
        // Picked backwards.
        assertEquals(LocalDate.of(2026, 10, 1) to LocalDate.of(2026, 10, 10), Dates.clampRange(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 1), today))
        // Start in the future used to give start > end.
        assertEquals(today to today, Dates.clampRange(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 9), today))
        assertEquals(LocalDate.of(2026, 10, 20) to today, Dates.clampRange(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 12, 1), today))
    }

    @Test
    fun parseInputAndDates() {
        assertEquals(125_050L, Money.parse("₹1,250.50"))
        assertEquals("Fri, 23 Oct", Dates.day(LocalDate.of(2026, 10, 23)))
        assertEquals("Fri, 23 Oct", Dates.day(TestData.at(LocalDate.of(2026, 10, 23), 23, 59)))
    }
}
