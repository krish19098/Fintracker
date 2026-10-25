package com.krish.spendsense.ui

import com.krish.spendsense.ui.SpendStyle.DEEP_RED
import com.krish.spendsense.ui.SpendStyle.LIGHT_RED
import com.krish.spendsense.ui.SpendStyle.STRONG_RED
import com.krish.spendsense.ui.SpendStyle.SURFACE_DARK
import com.krish.spendsense.ui.SpendStyle.SURFACE_LIGHT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpendStyleTest {
    private fun hex(c: Int) = "#%08X".format(c)

    @Test
    fun colourAtEachSpendLevel() {
        for (surface in listOf(SURFACE_LIGHT, SURFACE_DARK)) {
            assertEquals(hex(surface), hex(SpendStyle.background(0, surface)))
            assertEquals(hex(surface), hex(SpendStyle.background(49, surface)))
            assertEquals(hex(surface), hex(SpendStyle.background(50, surface)))
            assertEquals(hex(LIGHT_RED), hex(SpendStyle.background(75, surface)))
            assertEquals(hex(STRONG_RED), hex(SpendStyle.background(100, surface)))
            assertEquals(hex(DEEP_RED), hex(SpendStyle.background(101, surface)))
            assertEquals(hex(DEEP_RED), hex(SpendStyle.background(150, surface)))
        }
    }

    @Test
    fun blendIsSmoothBetweenStops() {
        val mid = SpendStyle.background(88, SURFACE_LIGHT)
        assertNotEquals(LIGHT_RED, mid)
        assertNotEquals(STRONG_RED, mid)
        // Monotonic: green channel only falls from 50% to 100% in light mode.
        val greens = (50..100).map { (SpendStyle.background(it, SURFACE_LIGHT) shr 8) and 0xFF }
        assertTrue(greens.zipWithNext().all { (a, b) -> b <= a })
        assertEquals(0xFF8899AA.toInt(), SpendStyle.blend(0xFF8899AA.toInt(), 0xFF000000.toInt(), 0f))
    }

    @Test
    fun textAlwaysMeetsWcagAa() {
        for (surface in listOf(SURFACE_LIGHT, SURFACE_DARK)) {
            for (pct in 0..200) {
                val bg = SpendStyle.background(pct, surface)
                val ratio = SpendStyle.contrast(SpendStyle.contentColor(bg), bg)
                assertTrue("pct=$pct bg=${hex(bg)} ratio=$ratio", ratio >= 4.5)
            }
        }
        assertEquals(SpendStyle.WHITE, SpendStyle.contentColor(DEEP_RED))
        assertEquals(SpendStyle.WHITE, SpendStyle.contentColor(SURFACE_DARK))
        assertEquals(SpendStyle.INK, SpendStyle.contentColor(SURFACE_LIGHT))
    }

    @Test
    fun accentMatchesRedsAndStaysTealOnTrack() {
        assertEquals(SpendStyle.TEAL, SpendStyle.accent(20, SURFACE_LIGHT))
        assertEquals(DEEP_RED, SpendStyle.accent(130, SURFACE_LIGHT))
        assertEquals(SpendStyle.background(90, SURFACE_DARK), SpendStyle.accent(90, SURFACE_DARK))
    }

    @Test
    fun moodSelection() {
        assertEquals(Mood.BIG_SMILE, SpendStyle.mood(0))
        assertEquals(Mood.BIG_SMILE, SpendStyle.mood(49))
        assertEquals(Mood.SMILE, SpendStyle.mood(50))
        assertEquals(Mood.SMILE, SpendStyle.mood(79))
        assertEquals(Mood.NEUTRAL, SpendStyle.mood(80))
        assertEquals(Mood.NEUTRAL, SpendStyle.mood(100))
        assertEquals(Mood.WORRIED, SpendStyle.mood(101))
        assertEquals(Mood.WORRIED, SpendStyle.mood(120))
        assertEquals(Mood.CRYING, SpendStyle.mood(121))
        assertEquals(Mood.CRYING, SpendStyle.mood(500))
    }

    @Test
    fun channelSelection() {
        assertEquals("txn_calm", SpendStyle.channel(0, true))
        assertEquals("txn_calm", SpendStyle.channel(79, true))
        assertEquals("txn_warning", SpendStyle.channel(80, true))
        assertEquals("txn_warning", SpendStyle.channel(100, true))
        assertEquals("txn_over", SpendStyle.channel(101, true))
        assertEquals("txn_plain", SpendStyle.channel(10, false))
        assertEquals("txn_plain_alert", SpendStyle.channel(90, false))
        assertEquals("txn_plain_alert", SpendStyle.channel(150, false))
        assertEquals("summaries_v2", SpendStyle.summaryChannel(true))
        assertEquals("summaries_plain", SpendStyle.summaryChannel(false))
    }

    @Test
    fun percentOfLimit() {
        assertEquals(0, SpendStyle.percent(5_000, 0))
        assertEquals(0, SpendStyle.percent(0, 100_000))
        assertEquals(75, SpendStyle.percent(75_000, 100_000))
        assertEquals(10_000, SpendStyle.percent(Long.MAX_VALUE / 200, 1))
    }
}
