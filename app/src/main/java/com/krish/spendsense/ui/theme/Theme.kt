package com.krish.spendsense.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krish.spendsense.domain.SpendBand
import com.krish.spendsense.ui.SpendPalettes

val Teal = Color(0xFF0F766E)
val Good = Color(0xFF16A34A)
val Warn = Color(0xFFF59E0B)
val Bad = Color(0xFFDC2626)

/** Large rupee figures for the key numbers. */
val AmountLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
val AmountMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)

/** Colours outside the Material scheme: hero gradient and the dark bottom bar. */
@Immutable
data class SpendColors(
    val heroStart: Color,
    val heroEnd: Color,
    val navBar: Color,
    val onNavBar: Color,
    val accent: Color,
) {
    val hero: Brush get() = Brush.linearGradient(listOf(heroStart, heroEnd))
}

val LocalSpendColors = staticCompositionLocalOf {
    val p = SpendPalettes.of(SpendBand.HEALTHY, false)
    SpendColors(Color(p.heroStart), Color(p.heroEnd), Color(p.navBar), Color(p.onNavBar), Color(p.accent))
}

private val AppTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
private fun animated(argb: Long): Color = animateColorAsState(Color(argb), tween(durationMillis = 600), label = "theme").value

/**
 * Mint / emerald design system whose colours follow today's spending [band] (healthy → approaching
 * → near → over). Changes cross-fade; dynamic colour stays off.
 */
@Composable
fun SpendSenseTheme(band: SpendBand = SpendBand.HEALTHY, dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = SpendPalettes.of(band, dark)
    val primary = animated(p.primary)
    val primaryContainer = animated(p.primaryContainer)
    val onPrimaryContainer = animated(p.onPrimaryContainer)
    val secondary = animated(p.secondary)
    val background = animated(p.background)
    val surfaceVariant = animated(p.surfaceVariant)
    val onSurfaceVariant = animated(p.onSurfaceVariant)
    val outline = animated(p.outline)
    val surface = Color(p.surface)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = primary,
        onPrimary = Color(p.onPrimary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = Color(p.onPrimary),
        secondaryContainer = primaryContainer,
        onSecondaryContainer = onPrimaryContainer,
        tertiary = secondary,
        background = background,
        onBackground = Color(p.onSurface),
        surface = surface,
        onSurface = Color(p.onSurface),
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = surfaceVariant,
        surfaceBright = surface,
        outline = outline,
        outlineVariant = surfaceVariant,
        error = if (dark) Color(0xFFFFB4AB) else Bad,
        errorContainer = if (dark) Color(0xFF93000A) else Color(0xFFFFDAD6),
    )
    val colors = SpendColors(animated(p.heroStart), animated(p.heroEnd), animated(p.navBar), Color(p.onNavBar), animated(p.accent))
    CompositionLocalProvider(LocalSpendColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
