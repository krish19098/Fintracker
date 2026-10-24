package com.krish.spendsense.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Teal = Color(0xFF0F766E)
val Good = Color(0xFF16A34A)
val Warn = Color(0xFFF59E0B)
val Bad = Color(0xFFDC2626)

private val Light = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCEFEA),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635F),
    secondaryContainer = Color(0xFFCCE8E2),
    background = Color(0xFFF7FAF9),
    surface = Color(0xFFF7FAF9),
    surfaceVariant = Color(0xFFDAE5E2),
    onSurface = Color(0xFF111917),
    onSurfaceVariant = Color(0xFF3F4947),
    error = Bad,
    errorContainer = Color(0xFFFFDAD6),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF5EDBC9),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFFA6F2E5),
    secondary = Color(0xFFB1CCC6),
    secondaryContainer = Color(0xFF334B47),
    background = Color(0xFF0E1514),
    surface = Color(0xFF0E1514),
    surfaceVariant = Color(0xFF3F4947),
    onSurface = Color(0xFFDDE4E1),
    onSurfaceVariant = Color(0xFFBEC9C6),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
)

/** Large rupee figures for the key numbers. */
val AmountLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
val AmountMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)

/** Custom calm, high-contrast theme; dynamic color is intentionally off. */
@Composable
fun SpendSenseTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Typography(), content = content)
}
