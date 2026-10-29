package com.krish.spendsense.domain

import com.krish.spendsense.data.ExcludedReason
import com.krish.spendsense.domain.TestData.TODAY
import com.krish.spendsense.domain.TestData.txn
import com.krish.spendsense.parse.Direction
import org.junit.Assert.assertEquals
import org.junit.Test

class SpendStateTest {
    private fun band(spentRupees: Long, limitRupees: Long = 1000, warn: Int = 80) =
        SpendState(spentRupees * 100, limitRupees * 100, warn).band

    @Test
    fun thresholdBoundaries() {
        assertEquals(SpendBand.HEALTHY, band(0))
        assertEquals(SpendBand.HEALTHY, band(499))
        assertEquals(SpendBand.APPROACHING, band(500))
        assertEquals(SpendBand.APPROACHING, band(799))
        assertEquals(SpendBand.NEAR, band(800))
        assertEquals(SpendBand.NEAR, band(999))
        assertEquals(SpendBand.OVER, band(1000))
        assertEquals(SpendBand.OVER, band(5000))
    }

    @Test
    fun nearUsesConfiguredWarningPercent() {
        assertEquals(SpendBand.APPROACHING, band(850, warn = 90))
        assertEquals(SpendBand.NEAR, band(900, warn = 90))
        // A warning % at or below 50 means there is no "approaching" step.
        assertEquals(SpendBand.NEAR, band(450, warn = 40))
    }

    @Test
    fun zeroOrMissingLimitIsSafe() {
        val s = SpendState(250_000, 0, 80)
        assertEquals(SpendBand.HEALTHY, s.band)
        assertEquals(0, s.percent)
        assertEquals(0f, s.fraction)
        assertEquals(SpendBand.HEALTHY, SpendState(250_000, -5, 80).band)
        assertEquals(10_000, SpendState(Long.MAX_VALUE / 200, 1, 80).percent)
    }

    @Test
    fun changedLimitChangesBand() {
        val rows = listOf(txn(900))
        assertEquals(SpendBand.NEAR, SpendState.of(rows, TODAY, 100_000, 80).band)
        assertEquals(SpendBand.APPROACHING, SpendState.of(rows, TODAY, 150_000, 80).band)
        assertEquals(SpendBand.OVER, SpendState.of(rows, TODAY, 80_000, 80).band)
    }

    @Test
    fun newDayResetsAndIstDateRollover() {
        val lateYesterday = txn(900, date = TODAY.minusDays(1), hour = 23, minute = 59)
        val earlyToday = txn(100, date = TODAY, hour = 0, minute = 1)
        val s = SpendState.of(listOf(lateYesterday, earlyToday), TODAY, 100_000, 80)
        assertEquals(10_000L, s.spentPaise)
        assertEquals(SpendBand.HEALTHY, s.band)
        assertEquals(0L, SpendState.of(listOf(lateYesterday), TODAY, 100_000, 80).spentPaise)
    }

    @Test
    fun editedDeletedAndReclassifiedRowsFollowDashboardRules() {
        val a = txn(600)
        val b = txn(300)
        assertEquals(SpendBand.NEAR, SpendState.of(listOf(a, b), TODAY, 100_000, 80).band)
        // Deleted.
        assertEquals(SpendBand.APPROACHING, SpendState.of(listOf(a), TODAY, 100_000, 80).band)
        // Edited amount.
        assertEquals(SpendBand.OVER, SpendState.of(listOf(a.copy(amountPaise = 120_000)), TODAY, 100_000, 80).band)
        // Reclassified as self-transfer / excluded.
        assertEquals(30_000L, SpendState.of(listOf(a.copy(excludedReason = ExcludedReason.SELF_TRANSFER), b), TODAY, 100_000, 80).spentPaise)
        // Credits never count; linked refunds net off.
        val refund = txn(200, direction = Direction.CREDIT, excluded = ExcludedReason.REFUND, linked = a.id)
        assertEquals(70_000L, SpendState.of(listOf(a, b, refund, txn(5000, direction = Direction.CREDIT)), TODAY, 100_000, 80).spentPaise)
        // Accounts left out of totals.
        assertEquals(0L, SpendState.of(listOf(a, b), TODAY, 100_000, 80, excludedAccounts = setOf(1L)).spentPaise)
    }

    @Test
    fun remainingAndFraction() {
        val s = SpendState(75_000, 100_000, 80)
        assertEquals(25_000L, s.remainingPaise)
        assertEquals(0.75f, s.fraction)
        assertEquals(75, s.percent)
        assertEquals(-20_000L, SpendState(120_000, 100_000, 80).remainingPaise)
    }
}
