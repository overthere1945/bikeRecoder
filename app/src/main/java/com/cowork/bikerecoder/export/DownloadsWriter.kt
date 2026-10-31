package com.cowork.bikerecoder.export

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import java.io.IOException

/**
 * Saves exported text into `Download/bikeRecoder/` through MediaStore Downloads (spec §4.2; no permission needed on
 * minSdk 30 for the app's own files). The entry is made pending, written as UTF-8, then published; if any step fails the
 * entry is deleted again and the error rethrown. Blocking IO: call it off the main thread.
 */
class DownloadsWriter(private val resolver: ContentResolver) : ExportSink {

    /** Writes [content] as [displayName] ([mime]) and returns the published entry. The phone may rename it ("x (1).gpx"). */
    fun write(displayName: String, mime: String, content: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(COLLECTION, values) ?: throw IOException("MediaStore did not create $displayName")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("MediaStore gave no stream for $uri")
            out.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
            val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(uri, publish, null, null) != 1) throw IOException("MediaStore did not publish $uri")
        } catch (e: Throwable) {
            try {
                resolver.delete(uri, null, null)
            } catch (cleanup: Exception) {
                e.addSuppressed(cleanup)
            }
            throw e
        }
        return uri
    }

    /** The name MediaStore recorded for [uri] (it may differ from the one asked for); null when the entry is gone. */
    fun displayName(uri: Uri): String? {
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE) }
        val cursor = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), args, null)
            ?: throw IOException("MediaStore query failed for $uri")
        return cursor.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    override fun save(displayName: String, mime: String, content: String): ExportResult.Saved {
        val uri = write(displayName, mime, content)
        // The file is saved by now; if its name cannot be read back, the one asked for is shown.
        val shown = try {
            displayName(uri)
        } catch (_: Exception) {
            null
        }
        return ExportResult.Saved(uri.toString(), shown ?: displayName, mime)
    }

    companion object {
        /** As MediaStore returns it (with the trailing `/`). */
        const val RELATIVE_PATH = "Download/bikeRecoder/"
        private val COLLECTION: Uri get() = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
}
