package com.krish.spendsense.ui

import com.krish.spendsense.domain.SpendBand
import com.krish.spendsense.domain.SpendState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpendPaletteTest {
    private fun ratio(a: Long, b: Long) = SpendStyle.contrast(a.toInt(), b.toInt())

    @Test
    fun everyBandMeetsWcagAaInLightAndDark() {
        for (dark in listOf(false, true)) for (band in SpendBand.entries) {
            val p = SpendPalettes.of(band, dark)
            val where = "$band dark=$dark"
            val text = listOf(
                "onSurface/surface" to ratio(p.onSurface, p.surface),
                "onSurface/background" to ratio(p.onSurface, p.background),
                "onSurfaceVariant/surface" to ratio(p.onSurfaceVariant, p.surface),
                "onSurfaceVariant/background" to ratio(p.onSurfaceVariant, p.background),
                "onPrimary/primary" to ratio(p.onPrimary, p.primary),
                "onPrimaryContainer/primaryContainer" to ratio(p.onPrimaryContainer, p.primaryContainer),
                "white/heroStart" to ratio(0xFFFFFFFF, p.heroStart),
                "white/heroEnd" to ratio(0xFFFFFFFF, p.heroEnd),
                "onNavBar/navBar" to ratio(p.onNavBar, p.navBar),
                "primary/surface (links, text buttons)" to ratio(p.primary, p.surface),
            )
            text.forEach { (name, r) -> assertTrue("$where $name = $r", r >= 4.5) }
            // Non-text UI (selected tab accent on the bar, primary on background): AA 3:1.
            assertTrue("$where accent/navBar", ratio(p.accent, p.navBar) >= 3.0)
            assertTrue("$where primary/background", ratio(p.primary, p.background) >= 3.0)
        }
    }

    @Test
    fun healthyIsMintAndEmerald() {
        val p = SpendPalettes.of(SpendBand.HEALTHY, false)
        assertEquals(0xFF0B5D45, p.primary)
        assertEquals(0xFFEEF8F2, p.background)
    }

    @Test
    fun statusLines() {
        assertEquals("No daily limit set", SpendPalettes.status(SpendState(50_000, 0, 80)))
        assertEquals("On track · ₹700 left", SpendPalettes.status(SpendState(30_000, 100_000, 80)))
        assertEquals("Past halfway · ₹400 left", SpendPalettes.status(SpendState(60_000, 100_000, 80)))
        assertEquals("Near your limit · ₹100 left", SpendPalettes.status(SpendState(90_000, 100_000, 80)))
        assertEquals("Daily limit reached", SpendPalettes.status(SpendState(100_000, 100_000, 80)))
        assertEquals("Over by ₹250", SpendPalettes.status(SpendState(125_000, 100_000, 80)))
    }
}
