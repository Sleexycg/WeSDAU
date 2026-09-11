package com.sdau.campuskit

import kotlin.math.roundToInt

/** Same dimensions as the wallpaper controls, independent of screen/semester height. */
internal object CourseDeleteCardLayout {
    fun bounds(width: Float, height: Float, density: Float, cardHeightPx: Float): CourseDropTarget {
        if (!width.isFinite() || !height.isFinite() || !density.isFinite() || !cardHeightPx.isFinite() ||
            width <= 0f || height <= 0f || density <= 0f || cardHeightPx <= 0f
        ) return CourseDropTarget(0f, 0f, 0f, 0f, 0f)
        val side = (FloatingBottomPanelMetrics.SideInsetDp * density).roundToInt().toFloat().coerceAtMost(width / 2f)
        val bottom = height - (FloatingBottomPanelMetrics.BottomInsetDp * density).roundToInt().toFloat().coerceAtMost(height)
        val top = (bottom - cardHeightPx.roundToInt()).coerceAtLeast(0f)
        return CourseDropTarget(side, top, width - side, bottom, FloatingBottomPanelMetrics.RadiusDp * density)
    }
}

/** Match the visible rounded drop area, excluding its transparent corners. */
internal data class CourseDropTarget(
    val left: Float, val top: Float, val right: Float, val bottom: Float, val radius: Float,
    val roundBottomCorners: Boolean = true
) {
    fun contains(x: Float, y: Float): Boolean {
        if (!x.isFinite() || !y.isFinite() || right <= left || bottom <= top ||
            x < left || x > right || y < top || y > bottom) return false
        val r = radius.coerceIn(0f, minOf(right - left, bottom - top) / 2f)
        val cx = x.coerceIn(left + r, right - r)
        val cy = if (roundBottomCorners) y.coerceIn(top + r, bottom - r) else y.coerceAtLeast(top + r)
        return (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r
    }
}
