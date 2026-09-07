package com.sdau.campuskit

import kotlin.math.roundToInt

/** One pixel-space layout for equal left/right/bottom insets and all four corners. */
internal object CourseDeleteCardLayout {
    fun bounds(width: Float, height: Float, density: Float, heightFraction: Float): CourseDropTarget {
        if (width <= 0f || height <= 0f) return CourseDropTarget(0f, 0f, 0f, 0f, 0f)
        val inset = (12f * density).roundToInt().toFloat().coerceIn(0f, minOf(width, height) / 2f)
        val top = (height * (1f - heightFraction.coerceIn(0f, 1f))).roundToInt().toFloat()
            .coerceIn(0f, height - inset)
        return CourseDropTarget(inset, top, width - inset, height - inset, 24f * density)
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
