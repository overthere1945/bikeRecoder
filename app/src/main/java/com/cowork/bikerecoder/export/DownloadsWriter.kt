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

    /**
     * Writes [content] as [displayName] ([mime]) and returns the published entry. When the name is taken it becomes
     * "x (1).gpx", "x (2).gpx", … ([numberedName]): the app picks a name not in the folder ([savedNames]) and, if the
     * phone still renamed the file on publishing (a file the app cannot see holds the name), renames it to the next
     * number ([settleName]). Left to itself a phone may name it "x.gpx (1)", which no app opens as GPX (the S25 did).
     */
    fun write(displayName: String, mime: String, content: String): Uri {
        val taken = try {
            savedNames()
        } catch (_: Exception) {
            emptySet()
        }
        val first = freeIndex(displayName, taken, 0)
        val uri = insertPending(numberedName(displayName, first), mime)
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
        // The file is saved by now; a name that cannot be settled stays as the phone made it.
        try {
            settleName(displayName, taken, first, recorded = { displayName(uri) }) { name ->
                val rename = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }
                resolver.update(uri, rename, null, null)
            }
        } catch (_: Exception) {
        }
        return uri
    }

    /** The names of the entries in `Download/bikeRecoder/` the app can see (its own, pending ones too). */
    private fun savedNames(): Set<String> {
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(RELATIVE_PATH))
        }
        val cursor = resolver.query(COLLECTION, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), args, null)
            ?: return emptySet()
        return cursor.use { c -> buildSet { while (c.moveToNext()) c.getString(0)?.let(::add) } }
    }

    private fun insertPending(name: String, mime: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(COLLECTION, values) ?: throw IOException("MediaStore did not create $name")
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
        /** Names tried after publishing before the phone's name is kept ([settleName]). */
        const val MAX_NAME_TRIES = 100

        /** "x.gpx" for 0, else "x (n).gpx" (the number goes before the extension). */
        fun numberedName(name: String, n: Int): String {
            if (n == 0) return name
            val dot = name.lastIndexOf('.')
            return if (dot <= 0) "$name ($n)" else "${name.substring(0, dot)} ($n)${name.substring(dot)}"
        }

        /** The first n ≥ [from] whose [numberedName] is not in [taken]. */
        fun freeIndex(name: String, taken: Set<String>, from: Int): Int {
            var n = from
            while (numberedName(name, n) in taken) n++
            return n
        }

        /**
         * After publishing under [numberedName] ([name], [first]): while the phone [recorded] another name (the name
         * was taken by a file the app cannot see), [rename]s to the next free number, up to [MAX_NAME_TRIES] times.
         * Returns the name the entry ends with (the phone's, if never settled; null when it is gone).
         */
        fun settleName(name: String, taken: Set<String>, first: Int, recorded: () -> String?, rename: (String) -> Unit): String? {
            var n = first
            repeat(MAX_NAME_TRIES) {
                val shown = recorded() ?: return null
                if (shown == numberedName(name, n)) return shown
                n = freeIndex(name, taken, n + 1)
                rename(numberedName(name, n))
            }
            return recorded()
        }

        /** As MediaStore returns it (with the trailing `/`). */
        const val RELATIVE_PATH = "Download/bikeRecoder/"
        private val COLLECTION: Uri get() = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
}
