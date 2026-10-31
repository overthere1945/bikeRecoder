package com.cowork.bikerecoder.importing

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.cowork.bikerecoder.core.importing.ImportItem
import com.cowork.bikerecoder.core.importing.ImportLimits
import com.cowork.bikerecoder.core.importing.ImportProblem
import com.cowork.bikerecoder.core.importing.ImportUnpacker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * The picked files (SAF or shared URIs, spec §3.3), behind an interface so the scanner and the job are tested on the JVM.
 * Every call blocks; callers make them off the main thread.
 */
interface ImportReader {
    /** The name shown in the list: the provider's display name, else the last piece of the URI. Never throws. */
    fun displayName(uri: String): String

    /** The file's bytes. Throws when the file is gone or no longer readable. */
    fun open(uri: String): InputStream

    /** Tries to keep read access past the screen that received [uris] (failures are ignored). */
    fun hold(uris: List<String>)

    /** Lets go of what [hold] kept (only those). */
    fun release(uris: List<String>)
}

/** [ImportReader] on the [android.content.ContentResolver]. */
class ContentImportReader(context: Context) : ImportReader {
    private val resolver = context.applicationContext.contentResolver

    override fun displayName(uri: String): String {
        val parsed = Uri.parse(uri)
        val name = try {
            resolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
        return name?.takeIf { it.isNotBlank() } ?: lastPiece(parsed, uri)
    }

    override fun open(uri: String): InputStream =
        resolver.openInputStream(Uri.parse(uri)) ?: throw FileNotFoundException("No stream for the picked file")

    private val grants = UriGrants(
        take = { resolver.takePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION) },
        drop = { resolver.releasePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION) },
    )

    override fun hold(uris: List<String>) = grants.hold(uris)

    override fun release(uris: List<String>) = grants.release(uris)

    private fun lastPiece(parsed: Uri, uri: String): String =
        (parsed.lastPathSegment ?: uri).substringAfterLast('/').ifBlank { uri }
}

/**
 * The persisted read grants of [ImportReader.hold] / [ImportReader.release]. Android keeps at most 512 persisted grants
 * per app and drops the oldest past that (which could be the music the video sheet keeps), so at most [limit] are taken
 * at a time; the other files stay on their live grant. [take] failures are ignored (shared URIs and plain grants cannot
 * be persisted). [release] drops only what was taken.
 */
internal class UriGrants(
    private val take: (String) -> Unit,
    private val drop: (String) -> Unit,
    private val limit: Int = MAX_PERSISTED_GRANTS,
) {
    private val held = mutableSetOf<String>()

    fun hold(uris: List<String>) {
        for (uri in uris) {
            val room = synchronized(held) { held.size < limit && uri !in held }
            if (!room) continue
            try {
                take(uri)
                synchronized(held) { held += uri }
            } catch (_: Exception) {
                // Not persistable: readable while its grant lasts.
            }
        }
    }

    fun release(uris: List<String>) {
        for (uri in uris) {
            val wasHeld = synchronized(held) { held.remove(uri) }
            if (!wasHeld) continue
            try {
                drop(uri)
            } catch (_: Exception) {
                // Already gone.
            }
        }
    }

    companion object {
        const val MAX_PERSISTED_GRANTS = 128
    }
}

/**
 * Opens the picked file [uri] on [io] and walks its items ([ImportUnpacker.visit]): [visitor] handles one item at a time
 * (so only that item's bytes are held) and returns false to stop. The stream is closed on every path, and a cancelled
 * caller stops at the next item. A file that cannot be opened, or whose reading fails (say a malformed ZIP entry name,
 * which [ImportUnpacker.visit] does not catch), ends with one [ImportItem.Unreadable] (UNREADABLE) numbered after the
 * items already given. Exceptions of [visitor] itself propagate.
 */
internal suspend fun ImportReader.walk(
    uri: String,
    displayName: String,
    limits: ImportLimits,
    io: CoroutineDispatcher,
    visitor: suspend (ImportItem) -> Boolean,
): Unit = withContext(io) {
    var next = 0
    var inVisitor = false
    var stopped = false
    try {
        open(uri).use { input ->
            ImportUnpacker.visit(displayName, input, limits) { item ->
                currentCoroutineContext().ensureActive()
                if (item.index >= next) next = item.index + 1
                inVisitor = true
                val goOn = visitor(item)
                inVisitor = false
                if (!goOn) stopped = true
                goOn
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (inVisitor) throw e
        // Opening (gone, no permission) or reading failed: the rest of the file is unreadable.
        if (!stopped) visitor(ImportItem.Unreadable(displayName, next, ImportProblem.UNREADABLE))
    }
}
