package com.sdau.campuskit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val BackgroundControlsTitleStyle = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
internal val BackgroundControlsValueStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold)

/** Measure the same header as the editor, including the user's font scale. */
@Composable
internal fun backgroundControlsPanelHeightPx(): Int {
    val measurer = rememberTextMeasurer()
    val headerHeight = maxOf(
        measurer.measure("背景清晰度", BackgroundControlsTitleStyle).size.height,
        measurer.measure("100%", BackgroundControlsValueStyle).size.height
    )
    return FloatingBottomPanelMetrics.heightPx(LocalDensity.current.density, headerHeight)
}

/** 1 = below the screen, 0 = settled. Shared with the wallpaper editor. */
@Composable
internal fun rememberFloatingBottomPanelMotion(visible: Boolean): Animatable<Float, AnimationVector1D> {
    val reveal = remember { Animatable(1f) }
    LaunchedEffect(visible) {
        reveal.animateTo(
            targetValue = if (visible) 0f else 1f,
            animationSpec = if (visible) {
                spring(dampingRatio = 0.82f, stiffness = 360f, visibilityThreshold = 0.001f)
            } else {
                tween(durationMillis = FloatingBottomPanelMetrics.ExitDurationMillis)
            }
        )
    }
    return reveal
}

internal fun Modifier.floatingBottomPanelMotion(reveal: Animatable<Float, AnimationVector1D>): Modifier =
    graphicsLayer {
        translationY = reveal.value * (size.height + FloatingBottomPanelMetrics.SlideClearanceDp.dp.toPx())
        alpha = 1f - reveal.value * 0.25f
    }
