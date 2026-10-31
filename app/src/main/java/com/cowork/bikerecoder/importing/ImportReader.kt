package com.cowork.bikerecoder.importing

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.importing.ImportItem
import com.cowork.bikerecoder.core.importing.ImportLimits
import com.cowork.bikerecoder.core.importing.ImportProblem
import com.cowork.bikerecoder.core.importing.ImportUnpacker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.FileNotFoundException
import java.io.IOException
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

/**
 * [ImportReader] on the [android.content.ContentResolver]. Files whose grant cannot be persisted (shared ones) are kept
 * open by [hold] as file descriptors, so they stay readable after the activity that received them finishes.
 */
class ContentImportReader(context: Context, private val log: WarnLog = androidWarnLog("ImportReader")) : ImportReader {
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

    override fun open(uri: String): InputStream {
        (grants.kept(uri) as? ParcelFileDescriptor)?.let { pfd -> keptStream(uri, pfd)?.let { return it } }
        return resolver.openInputStream(Uri.parse(uri)) ?: throw FileNotFoundException("No stream for the picked file")
    }

    /**
     * A stream of its own over the kept [pfd], from the start. A pipe cannot seek: it is read this once and let go. Null
     * when the descriptor is no longer usable (then the URI is opened as usual).
     */
    private fun keptStream(uri: String, pfd: ParcelFileDescriptor): InputStream? {
        val stream = try {
            ParcelFileDescriptor.AutoCloseInputStream(pfd.dup())
        } catch (e: IOException) {
            log.warn("Could not reuse the kept file", e)
            grants.forget(uri)
            return null
        }
        try {
            stream.channel.position(0)
        } catch (_: IOException) {
            // A pipe: what is left of it is read once, as is.
            grants.forget(uri)
        }
        return stream
    }

    private val grants = UriGrants(
        take = { resolver.takePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION) },
        drop = { resolver.releasePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION) },
        keep = { resolver.openFileDescriptor(Uri.parse(it), "r") },
        onKeepFailed = { _, e -> log.warn("Could not keep a shared file open", e) },
    )

    override fun hold(uris: List<String>) = grants.hold(uris)

    override fun release(uris: List<String>) = grants.release(uris)

    private fun lastPiece(parsed: Uri, uri: String): String =
        (parsed.lastPathSegment ?: uri).substringAfterLast('/').ifBlank { uri }
}

/**
 * The read access of [ImportReader.hold] / [ImportReader.release]. Android keeps at most 512 persisted grants per app and
 * drops the oldest past that (which could be the music the video sheet keeps), so at most [limit] are taken at a time.
 * [take] failures are ignored (shared URIs and plain grants cannot be persisted).
 *
 * A file not persisted (the take failed, or past [limit]) lives on a grant that ends with the activity that received it,
 * so it is kept open instead ([keep], a file descriptor) while there is room, at most [keepLimit] at a time; a failed
 * [keep] goes to [onKeepFailed] and the file stays on its live grant. [release] drops only what was taken and closes
 * only what was kept.
 */
internal class UriGrants(
    private val take: (String) -> Unit,
    private val drop: (String) -> Unit,
    private val keep: (String) -> Closeable? = { null },
    private val limit: Int = MAX_PERSISTED_GRANTS,
    private val keepLimit: Int = MAX_KEPT_DESCRIPTORS,
    private val onKeepFailed: (String, Exception) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private val held = mutableSetOf<String>()
    private val kept = LinkedHashMap<String, Closeable>()

    fun hold(uris: List<String>) {
        for (uri in uris) {
            val (known, room) = synchronized(lock) { (uri in held || uri in kept) to (held.size < limit) }
            if (known) continue
            if (room && persist(uri)) continue
            keepOpen(uri)
        }
    }

    private fun persist(uri: String): Boolean = try {
        take(uri)
        synchronized(lock) { held += uri }
        true
    } catch (_: Exception) {
        // Not persistable: kept open below if there is room.
        false
    }

    private fun keepOpen(uri: String) {
        if (synchronized(lock) { kept.size >= keepLimit }) return
        val descriptor = try {
            keep(uri) ?: return
        } catch (e: Exception) {
            onKeepFailed(uri, e)
            return
        }
        synchronized(lock) { kept.put(uri, descriptor) }?.let(::closeQuietly)
    }

    /** The descriptor [hold] kept for [uri], if any. */
    fun kept(uri: String): Closeable? = synchronized(lock) { kept[uri] }

    /** Closes and lets go of [uri]'s kept descriptor (no longer usable). */
    fun forget(uri: String) {
        synchronized(lock) { kept.remove(uri) }?.let(::closeQuietly)
    }

    fun release(uris: List<String>) {
        for (uri in uris) {
            forget(uri)
            val wasHeld = synchronized(lock) { held.remove(uri) }
            if (!wasHeld) continue
            try {
                drop(uri)
            } catch (_: Exception) {
                // Already gone.
            }
        }
    }

    private fun closeQuietly(c: Closeable) {
        try {
            c.close()
        } catch (_: Exception) {
            // Closing is best effort.
        }
    }

    companion object {
        const val MAX_PERSISTED_GRANTS = 128
        const val MAX_KEPT_DESCRIPTORS = 256
    }
}

/**
 * Opens the picked file [uri] on [io] and walks its items ([ImportUnpacker.visit]): [visitor] handles one item at a time
 * (so only that item's bytes are held) and returns false to stop. The stream is closed on every path, and a cancelled
 * caller stops at the next item. A file that cannot be opened, or whose reading fails (say a malformed ZIP entry name,
 * which [ImportUnpacker.visit] does not catch), ends with one [ImportItem.Unreadable] (UNREADABLE) numbered after the
 * items already given; when the reading ran out of memory ([OutOfMemoryError]) that item is TOO_LARGE. Exceptions and
 * errors of [visitor] itself propagate.
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
    } catch (e: OutOfMemoryError) {
        if (inVisitor) throw e
        // The reading itself (say one huge item) ran out of memory: what it held is garbage now.
        if (!stopped) visitor(ImportItem.Unreadable(displayName, next, ImportProblem.TOO_LARGE))
    }
}
