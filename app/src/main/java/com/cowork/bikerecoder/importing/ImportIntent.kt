package com.cowork.bikerecoder.importing

import android.content.Intent
import java.util.concurrent.atomic.AtomicReference

/**
 * The files shared or opened with the app (V1.6.0 spec §1.1), as URI strings: [Intent.ACTION_SEND]'s [stream],
 * [Intent.ACTION_SEND_MULTIPLE]'s [streams], [Intent.ACTION_VIEW]'s [data]. A send without a stream falls back to its
 * ClipData items ([clip]). Only content URIs (SAF and shared grants), each once; none when the intent was reopened from
 * the recent apps ([fromHistory]: it was handled when it came).
 */
internal fun importUris(
    action: String?,
    stream: String?,
    streams: List<String>,
    data: String?,
    clip: List<String>,
    fromHistory: Boolean,
): List<String> {
    if (fromHistory) return emptyList()
    val uris = when (action) {
        Intent.ACTION_SEND -> listOfNotNull(stream).ifEmpty { clip }
        Intent.ACTION_SEND_MULTIPLE -> streams.ifEmpty { clip }
        Intent.ACTION_VIEW -> listOfNotNull(data)
        else -> emptyList()
    }
    return uris.filter { it.startsWith("content://") }.distinct()
}

/**
 * The picked files on their way to the import screen, in memory only (the list can be thousands of URIs, too many for a
 * navigation route or saved state). [put] before opening the screen; the screen [take]s them once.
 */
class ImportHandoff {
    private val uris = AtomicReference<List<String>?>(null)

    fun put(list: List<String>) {
        uris.set(list)
    }

    /** The files handed over, once; null when none were (or they were taken). */
    fun take(): List<String>? = uris.getAndSet(null)
}
