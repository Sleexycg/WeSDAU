package com.sdau.campuskit

/** Only the removed rows are restored: never roll unrelated cache edits back. */
internal class CourseDeletionUndo<T> private constructor(
    val remaining: List<T>,
    private val removed: List<IndexedValue<T>>
) {
    val hasRemovedCourses: Boolean get() = removed.isNotEmpty()

    fun restoreInto(current: List<T>, sameRecord: (T, T) -> Boolean): List<T> {
        val restored = current.toMutableList()
        val matched = BooleanArray(current.size)
        removed.forEach { entry ->
            // A refresh may already have restored this row. Match each current
            // occurrence at most once, preserving legitimate duplicate rows too.
            val present = current.indices.firstOrNull { !matched[it] && sameRecord(current[it], entry.value) }
            if (present != null) matched[present] = true
            else restored.add(entry.index.coerceIn(0, restored.size), entry.value)
        }
        return restored
    }

    companion object {
        fun <T> capture(source: List<T>, matches: (T) -> Boolean): CourseDeletionUndo<T> {
            val remaining = mutableListOf<T>()
            val removed = mutableListOf<IndexedValue<T>>()
            source.forEachIndexed { index, value ->
                if (matches(value)) removed += IndexedValue(index, value) else remaining += value
            }
            return CourseDeletionUndo(remaining, removed)
        }
    }
}
