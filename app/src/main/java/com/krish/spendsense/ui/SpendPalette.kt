package com.krish.spendsense.ui

import com.krish.spendsense.domain.Money
import com.krish.spendsense.domain.SpendBand
import com.krish.spendsense.domain.SpendState

/**
 * App colours for one spending band (ARGB). Healthy is the mint / emerald base look; the other bands
 * warm it towards amber and red. Pure Kotlin so contrast is unit-tested.
 */
data class SpendPalette(
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
    val secondary: Long,
    val background: Long,
    val surface: Long,
    val surfaceVariant: Long,
    val onSurface: Long,
    val onSurfaceVariant: Long,
    val outline: Long,
    /** Hero card / widget gradient; white text sits on both ends. */
    val heroStart: Long,
    val heroEnd: Long,
    val navBar: Long,
    val onNavBar: Long,
    /** Selected tab / accent on dark surfaces (mint, gold, amber, coral). */
    val accent: Long,
)

object SpendPalettes {
    private const val WHITE = 0xFFFFFFFF

    private val light = mapOf(
        SpendBand.HEALTHY to SpendPalette(
            primary = 0xFF0B5D45, onPrimary = WHITE, primaryContainer = 0xFFD3F2E2, onPrimaryContainer = 0xFF00382A,
            secondary = 0xFF2E8B63, background = 0xFFEEF8F2, surface = WHITE, surfaceVariant = 0xFFE1F0E8,
            onSurface = 0xFF0F1F19, onSurfaceVariant = 0xFF4A5D55, outline = 0xFFB9CEC4,
            heroStart = 0xFF0B5D45, heroEnd = 0xFF15805B, navBar = 0xFF0C3B2E, onNavBar = 0xFFCFE9DC, accent = 0xFF3DDC97,
        ),
        SpendBand.APPROACHING to SpendPalette(
            primary = 0xFF4D6B0F, onPrimary = WHITE, primaryContainer = 0xFFE8F0CC, onPrimaryContainer = 0xFF2A3A06,
            secondary = 0xFF6E7D12, background = 0xFFF5F8EA, surface = WHITE, surfaceVariant = 0xFFEBF0D9,
            onSurface = 0xFF1B2010, onSurfaceVariant = 0xFF55603F, outline = 0xFFCBD3AE,
            heroStart = 0xFF3F6212, heroEnd = 0xFF7A5E08, navBar = 0xFF2F3F0C, onNavBar = 0xFFE6EFC9, accent = 0xFFE3C14B,
        ),
        SpendBand.NEAR to SpendPalette(
            primary = 0xFFA2410A, onPrimary = WHITE, primaryContainer = 0xFFFDE5C8, onPrimaryContainer = 0xFF4A1E00,
            secondary = 0xFFAD5A0E, background = 0xFFFFF6EA, surface = WHITE, surfaceVariant = 0xFFF8EAD8,
            onSurface = 0xFF241A10, onSurfaceVariant = 0xFF6B5340, outline = 0xFFE5CBAE,
            heroStart = 0xFF8A2E0E, heroEnd = 0xFFB34A0A, navBar = 0xFF4A2408, onNavBar = 0xFFFBE3CC, accent = 0xFFFFB547,
        ),
        SpendBand.OVER to SpendPalette(
            primary = 0xFFB3261E, onPrimary = WHITE, primaryContainer = 0xFFFAD6D3, onPrimaryContainer = 0xFF410E0B,
            secondary = 0xFFC0392B, background = 0xFFFFF1F0, surface = WHITE, surfaceVariant = 0xFFF8E2E0,
            onSurface = 0xFF261414, onSurfaceVariant = 0xFF6E4A47, outline = 0xFFE8C2BE,
            heroStart = 0xFF7F1D1D, heroEnd = 0xFFB71C1C, navBar = 0xFF4C1414, onNavBar = 0xFFFAD9D6, accent = 0xFFFF8A80,
        ),
    )

    private val dark = mapOf(
        SpendBand.HEALTHY to SpendPalette(
            primary = 0xFF6FDDB0, onPrimary = 0xFF00382A, primaryContainer = 0xFF0E4A37, onPrimaryContainer = 0xFFBDF3DA,
            secondary = 0xFF8FD9B6, background = 0xFF0B1411, surface = 0xFF13201B, surfaceVariant = 0xFF1D2D27,
            onSurface = 0xFFE2EFE8, onSurfaceVariant = 0xFFA9BDB4, outline = 0xFF3A4D45,
            heroStart = 0xFF0B4D3A, heroEnd = 0xFF117052, navBar = 0xFF07261D, onNavBar = 0xFFBFE3D2, accent = 0xFF3DDC97,
        ),
        SpendBand.APPROACHING to SpendPalette(
            primary = 0xFFC8D872, onPrimary = 0xFF2A3300, primaryContainer = 0xFF3B4A10, onPrimaryContainer = 0xFFE6F0B5,
            secondary = 0xFFD4CF80, background = 0xFF12140B, surface = 0xFF1C2012, surfaceVariant = 0xFF282E1A,
            onSurface = 0xFFE9EDDA, onSurfaceVariant = 0xFFB8BE9F, outline = 0xFF464C33,
            heroStart = 0xFF33500E, heroEnd = 0xFF6A5207, navBar = 0xFF1D2508, onNavBar = 0xFFE3EBC4, accent = 0xFFE3C14B,
        ),
        SpendBand.NEAR to SpendPalette(
            primary = 0xFFFFB870, onPrimary = 0xFF4A2400, primaryContainer = 0xFF6B3410, onPrimaryContainer = 0xFFFFDCC0,
            secondary = 0xFFF0B98A, background = 0xFF17110B, surface = 0xFF221A12, surfaceVariant = 0xFF30251A,
            onSurface = 0xFFF2E6DA, onSurfaceVariant = 0xFFCDB8A4, outline = 0xFF544233,
            heroStart = 0xFF7C2D0E, heroEnd = 0xFFA0430A, navBar = 0xFF2E1606, onNavBar = 0xFFF8DFC6, accent = 0xFFFFB547,
        ),
        SpendBand.OVER to SpendPalette(
            primary = 0xFFFF9C94, onPrimary = 0xFF5C0F0B, primaryContainer = 0xFF7A1E1A, onPrimaryContainer = 0xFFFFDAD6,
            secondary = 0xFFF2A9A3, background = 0xFF180D0D, surface = 0xFF241414, surfaceVariant = 0xFF331E1E,
            onSurface = 0xFFF5E1E0, onSurfaceVariant = 0xFFD5B5B2, outline = 0xFF5A3A38,
            heroStart = 0xFF6B1414, heroEnd = 0xFF9F1C1C, navBar = 0xFF2F0A0A, onNavBar = 0xFFF8D6D3, accent = 0xFFFF8A80,
        ),
    )

    fun of(band: SpendBand, darkMode: Boolean): SpendPalette = (if (darkMode) dark else light).getValue(band)

    /** One-line status shared by Home and the widget. */
    fun status(state: SpendState): String = when {
        !state.hasLimit -> "No daily limit set"
        state.band == SpendBand.OVER && state.remainingPaise == 0L -> "Daily limit reached"
        state.band == SpendBand.OVER -> "Over by ${Money.short(-state.remainingPaise)}"
        state.band == SpendBand.NEAR -> "Near your limit · ${Money.short(state.remainingPaise)} left"
        state.band == SpendBand.APPROACHING -> "Past halfway · ${Money.short(state.remainingPaise)} left"
        else -> "On track · ${Money.short(state.remainingPaise)} left"
    }
}
