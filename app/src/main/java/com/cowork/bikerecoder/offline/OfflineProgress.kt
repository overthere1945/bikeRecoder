package com.cowork.bikerecoder.offline

/**
 * Corridor download progress. [completed]/[required] count MapLibre resources of the corridor region
 * (before the first status arrives, [started] is false and [required] is the planner's tile estimate).
 * A non-null [error] means the download failed and its partial regions were removed; the UI shows nothing
 * for it (it is logged).
 */
data class OfflineProgress(
    val tripId: Long,
    val completed: Long,
    val required: Long,
    val estimatedBytes: Long,
    val error: String? = null,
    val started: Boolean = false,
) {
    /** 0..100, or 0 while nothing is known. */
    val percent: Int
        get() = if (required <= 0) 0 else (completed * 100 / required).coerceIn(0, 100).toInt()

    /** The on-screen chip text, or null when nothing should be shown (failed downloads are not shown). */
    val chipText: String?
        get() = when {
            error != null -> null
            !started -> "지도 저장 준비 · 약 ${estimatedMegabytes} MB"
            else -> "지도 저장 중 ${percent}%"
        }

    private val estimatedMegabytes: Long
        get() = ((estimatedBytes + 999_999) / 1_000_000).coerceAtLeast(1)
}
