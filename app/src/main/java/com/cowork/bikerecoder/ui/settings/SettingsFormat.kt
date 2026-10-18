package com.cowork.bikerecoder.ui.settings

import java.util.Locale

/** "0 B", "512 KB", "81.2 MB", "1.3 GB" (1024-based, one decimal from MB on). */
internal fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < mb -> String.format(Locale.US, "%.0f KB", bytes / kb)
        bytes < gb -> String.format(Locale.US, "%.1f MB", bytes / mb)
        else -> String.format(Locale.US, "%.1f GB", bytes / gb)
    }
}
