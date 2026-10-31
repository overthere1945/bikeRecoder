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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
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
    private val held = mutableSetOf<String>()

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

    override fun hold(uris: List<String>) {
        for (uri in uris) {
            try {
                resolver.takePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                synchronized(held) { held += uri }
            } catch (_: Exception) {
                // Shared URIs and plain grants cannot be persisted; they stay readable while the grant lasts.
            }
        }
    }

    override fun release(uris: List<String>) {
        for (uri in uris) {
            val wasHeld = synchronized(held) { held.remove(uri) }
            if (!wasHeld) continue
            try {
                resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
                // Already gone.
            }
        }
    }

    private fun lastPiece(parsed: Uri, uri: String): String =
        (parsed.lastPathSegment ?: uri).substringAfterLast('/').ifBlank { uri }
}

/**
 * The items of the picked file [uri] ([ImportUnpacker.visit]), read on [io] one at a time: the reading waits while the
 * collector works on an item, so at most one item's bytes are held. When the collector stops early (or is cancelled)
 * the reading stops too. A file that cannot be opened, or whose reading fails, ends with one
 * [ImportItem.Unreadable] (UNREADABLE) numbered after the items already given.
 *
 * [io] must not be the single thread the collector runs on: the reading blocks while it waits.
 */
internal fun ImportReader.items(
    uri: String,
    displayName: String,
    limits: ImportLimits,
    io: CoroutineDispatcher,
): Flow<ImportItem> = channelFlow {
    var next = 0
    try {
        open(uri).use { input ->
            ImportUnpacker.visit(displayName, input, limits) { item ->
                if (item.index >= next) next = item.index + 1
                if (!trySendBlocking(item).isSuccess) throw CancellationException("The collector stopped")
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Opening (gone, no permission) or reading (a malformed ZIP entry name) failed: the rest is unreadable.
        send(ImportItem.Unreadable(displayName, next, ImportProblem.UNREADABLE))
    }
}.buffer(Channel.RENDEZVOUS).flowOn(io)
