package com.krish.spendsense.ui

import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.krish.spendsense.R

@DrawableRes
fun coinRes(mood: Mood): Int = when (mood) {
    Mood.BIG_SMILE -> R.drawable.coin_big_smile
    Mood.SMILE -> R.drawable.coin_smile
    Mood.NEUTRAL -> R.drawable.coin_neutral
    Mood.WORRIED -> R.drawable.coin_worried
    Mood.CRYING -> R.drawable.coin_crying
}

/**
 * The coin mascot. When the mood changes it pops with a gentle spring bounce, unless the user
 * turned animations off (Developer options / Accessibility "Remove animations").
 */
@Composable
fun MoodCoin(mood: Mood, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val animate = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val scale = remember { Animatable(1f) }
    val shown = remember { arrayOf(mood) }
    LaunchedEffect(mood) {
        if (shown[0] == mood) return@LaunchedEffect
        shown[0] = mood
        if (!animate) return@LaunchedEffect
        scale.snapTo(0.8f)
        scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    Image(
        painterResource(coinRes(mood)),
        contentDescription = "Coin mascot, ${mood.label}",
        modifier = modifier.size(size).graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        },
    )
}
