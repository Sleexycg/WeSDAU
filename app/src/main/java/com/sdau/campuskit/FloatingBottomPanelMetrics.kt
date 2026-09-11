package com.sdau.campuskit

import kotlin.math.roundToInt

/** Dimensions of the wallpaper controls, also used by the course deletion card. */
internal object FloatingBottomPanelMetrics {
    const val SideInsetDp = 6f
    const val BottomInsetDp = 12f
    const val RadiusDp = 30f
    const val ContentSideDp = 22f
    const val ContentTopDp = 22f
    const val ContentBottomDp = 34f
    const val ContentGapDp = 16f
    const val SliderHeightDp = 24f
    const val ActionHeightDp = 60f
    const val ExitDurationMillis = 180
    const val RemovalDelayMillis = 210L
    const val SlideClearanceDp = 28f

    fun heightPx(density: Float, headerHeightPx: Int): Int =
        (ContentTopDp * density).roundToInt() +
            (ContentBottomDp * density).roundToInt() +
            2 * (ContentGapDp * density).roundToInt() +
            (SliderHeightDp * density).roundToInt() +
            (ActionHeightDp * density).roundToInt() + headerHeightPx
}
